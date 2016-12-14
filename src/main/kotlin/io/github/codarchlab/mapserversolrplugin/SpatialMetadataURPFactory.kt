/*
 * Copyright 2016 David Smiley
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.codarchlab.mapserversolrplugin

import org.apache.solr.common.SolrException
import org.apache.solr.common.SolrInputDocument
import org.apache.solr.common.params.SolrParams
import org.apache.solr.common.util.NamedList
import org.apache.solr.request.SolrQueryRequest
import org.apache.solr.response.SolrQueryResponse
import org.apache.solr.update.AddUpdateCommand
import org.apache.solr.update.processor.UpdateRequestProcessor
import org.apache.solr.update.processor.UpdateRequestProcessorFactory
import org.w3c.dom.Element
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import java.util.stream.Collectors
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.reflect.KProperty

/**
 * Adds spatial metadata based on spatial metadata, possibly extracted from a raster file.
 */
class SpatialMetadataURPFactory : UpdateRequestProcessorFactory() {

  private val initArgs: NamedList<Any> = NamedList()

  private val defaultBasePath: String? by initArgs // optional
  private val tileMapResourcePathField: String by initArgs
  private val bboxField: String by initArgs
  private val pointField: String by initArgs
  private val areaField: String by initArgs
  private val zoomLevelsField: String by initArgs
  private val imageZXYPathsField: String by initArgs
  private val tileFormatExtensionField: String by initArgs

  private val xmlDocBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()!!

  override fun init(args: NamedList<Any>) {
    // note we can't simply set the field to this parameter due to Kotlin delegated property limitation
    initArgs.addAll(args)
  }

  override fun getInstance(req: SolrQueryRequest, rsp: SolrQueryResponse?,
                           next: UpdateRequestProcessor?): UpdateRequestProcessor {
    return SpatialMetadataURP(req.params, next)
  }

  inner class SpatialMetadataURP(params: SolrParams, next: UpdateRequestProcessor?) : UpdateRequestProcessor(next) {

    val baseUrlPath = (params.get("basePath") ?: defaultBasePath)?.let {
      val url = if (isUrl(it)) it else Paths.get(it).toUri().toURL().toString()
      if (url.endsWith('/')) url else "$url/"
    } //?: throw SolrException(SolrException.ErrorCode.BAD_REQUEST, "basePath must be specified")

    override fun processAdd(cmd: AddUpdateCommand) {
      val solrDoc = cmd.solrDoc!!
      val tileRsrcInput = solrDoc.getFieldValue(tileMapResourcePathField) as String?
      if (tileRsrcInput != null) {
        // convert to a URL
        try {
          val tileRsrcUrl = constructTileRsrcUrl(baseUrlPath, tileRsrcInput)

          enrichDocFromTileMap(solrDoc, tileRsrcUrl)
        } catch(e: Exception) {
          throw SolrException(SolrException.ErrorCode.BAD_REQUEST,
                  "While processing doc ${cmd.printableId} file path $tileRsrcInput: $e", e)
        }
      }

      super.processAdd(cmd)
    }

    private fun constructTileRsrcUrl(basePath: String?, tilePathStr: String): String {
      var tileRsrcUrl = tilePathStr
      if (!isUrl(tileRsrcUrl)) {
        if (tileRsrcUrl.startsWith('/')) {
          tileRsrcUrl = "file://$tileRsrcUrl"
        } else {
          if (basePath == null) {
            throw SolrException(SolrException.ErrorCode.BAD_REQUEST, "can't resolve $tileMapResourcePathField=$tileRsrcUrl")
          }
          tileRsrcUrl = basePath + tileRsrcUrl
        }
      }
      // append '/' and 'tilemapresource.xml' if doesn't already end with ".xml"
      if (tileRsrcUrl.endsWith(".xml") == false) {
        if (!tileRsrcUrl.endsWith('/')) {
          tileRsrcUrl += '/'
        }
        tileRsrcUrl += "/tilemapresource.xml"
      }
      return tileRsrcUrl
    }

    private fun enrichDocFromTileMap(solrDoc: SolrInputDocument, tileRsrcUrl: String) {
      val xmlTileMap = xmlDocBuilder.parse(tileRsrcUrl).documentElement
      checkEquals("TileMap", xmlTileMap.nodeName, "Not a <TileMap>")

      // TODO map from XML to Solr fields:
      //    Title, Abstract, KeywordList, Attribution Title, Attribution Logo, WebMapContext

      val xmlSRS = xmlTileMap.getElementByName("SRS")
      checkEquals("EPSG:900913", xmlSRS.textContent, "unexpected SRS") // web mercator

      // BoundingBox
      // note: gdal2tiles is broken does lat/lon not SRS: https://trac.osgeo.org/gdal/ticket/2737
      // TODO how do we detect which? and if in SRS then how convert to lat/lon?
      val xmlBB = xmlTileMap.getElementByName("BoundingBox")
      val bbox = Spatial4jCTX.rect(
              minX = xmlBB.getAttribute("minx").toDouble(),
              maxX = xmlBB.getAttribute("maxx").toDouble(),
              minY = xmlBB.getAttribute("miny").toDouble(),
              maxY = xmlBB.getAttribute("maxy").toDouble()
      )
      solrDoc.addField(bboxField, Spatial4jCTX.formats.wktWriter.toString(bbox))
      solrDoc.addField(pointField, Spatial4jCTX.formats.wktWriter.toString(bbox.center))
      solrDoc.addField(areaField, bbox.getArea(Spatial4jCTX))

      // Origin.  assert origin is minx, miny of bbox
      val xmlOrigin = xmlTileMap.getElementByName("Origin")
      checkEquals(bbox.minX, xmlOrigin.getAttribute("x").toDouble(), "bad x Origin")
      checkEquals(bbox.minY, xmlOrigin.getAttribute("y").toDouble(), "bad y Origin")

      // TileFormat
      val xmlTileFormat = xmlTileMap.getElementByName("TileFormat")
      val tileFormatExtension = xmlTileFormat.getAttribute("extension")
      solrDoc.addField(tileFormatExtensionField, tileFormatExtension)

      // TileSets for 'z' levels
      val xmlTileSets = xmlTileMap.getElementByName("TileSets")
      checkEquals("mercator", xmlTileSets.getAttribute("profile"), "unexpected profile")

      val zList: List<Int> = xmlTileSets.getElementsByName("TileSet").map { xmlTileSet ->
        val z = xmlTileSet.getAttribute("order").toInt()
        // This formula is for "global-mercator"
        // TODO the "z-1" looks wrong, per the spec; should be just 'z' ?
        val expected = 78271.516 / Math.pow(2.0, (z-1).toDouble())
        val actual = xmlTileSet.getAttribute("units-per-pixel").toDouble()
        check(Math.abs(1 - expected / actual) < 0.001, {"Unexpected units-per-pixel"})
        z // thus we get a list of 'z' vals
      }.sorted()
      // check zList are contiguous (no missing)
      check(zList.size == zList.last() - zList.first() + 1, {"Missing TileSets? $zList"})
      solrDoc.addField(zoomLevelsField, zList)

      // add Z/X/Y image paths by crawling file system
      val zxyPaths = crawlZXYPaths(tileRsrcUrl, tileFormatExtension)
      check(!zxyPaths.isEmpty(), {"Found no images files."})
      solrDoc.addField(imageZXYPathsField, zxyPaths)
    }

    private fun crawlZXYPaths(tileRsrcUrl: String, tileFormatExtension: String?): List<String> {
      check(tileRsrcUrl.startsWith("file:/"), {"Only file paths are supported at this time. Got: $tileRsrcUrl"})
      val pathStr = URLDecoder.decode(tileRsrcUrl.substring("file:".length), "UTF8") // remove % encoding
      val parentPath = Paths.get(pathStr).parent
      // rexexp encloses the "Z/X/Y" portion of the overall path
      val regexp = Regex(".*/(\\d+/\\d+/\\d+)\\.$tileFormatExtension")
      return Files.walk(parentPath, 3).use {
        it.map { it -> regexp.matchEntire(it.toString())}
                .filter { it != null }
                .map { it!!.groupValues[1] }
                .collect(Collectors.toList<String>())
      }
    }

  } // URP

} // URP Factory

fun isUrl(str: String): Boolean = str.matches(Regex("^[a-z]+:/.*"))

fun <T> checkEquals(expected: T?, got: T?, message: String = "") =
        check(expected == got, { "$message Expected $expected got $got" })

inline operator fun <reified T, P> NamedList<*>.getValue(t: P, property: KProperty<*>): T {
  val name = property.name
  return (get(name) as T?) ?: throw SolrException(SolrException.ErrorCode.SERVER_ERROR, "expected $name")
}

fun Element.getElementsByName(name: String): List<Element> {
  val nodeList = this.getElementsByTagName(name)
  return object : AbstractList<Element>() {
    override fun get(index: Int): Element = nodeList.item(index) as Element
    override val size: Int get() = nodeList.length
  }
}

fun Element.getElementByName(name: String): Element {
  val nodeList = this.getElementsByTagName(name)
  check(nodeList.length == 1, { "Expected one child element <$name> in the xml" })
  return nodeList.item(0) as Element
}