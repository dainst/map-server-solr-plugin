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

import org.apache.solr.client.solrj.SolrClient
import org.apache.solr.client.solrj.impl.HttpSolrClient
import org.apache.solr.common.params.ModifiableSolrParams
import java.awt.AlphaComposite
import java.awt.color.ColorSpace
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.awt.image.ColorConvertOp
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import javax.imageio.ImageIO
import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty


/**
 * A Java servlet that returns an image for a map tile delineated by X,Y,Z parameters defined by
 * https://wiki.osgeo.org/wiki/Tile_Map_Service_Specification (TMS).  The request also includes
 * search parameters to be passed to Apache Solr, which will return multiple documents that have
 * metadata pointing to imagery.
 *
 * An example request looks like: /tile/Z/Y/X.png?q=keyword&etc.
 */
class TmsSolrServlet : HttpServlet() {

  // Kotlin delegated property to ServletConfig init parameters
  val servletConfigDelegate = object : ReadOnlyProperty<Any,String> {
    override fun getValue(thisRef: Any, property: KProperty<*>): String {
      val name = property.name
      return servletConfig.getInitParameter(name) ?: throw RuntimeException("Expected servlet init param '$name'")
    }
  }

  val solrCollection: String by servletConfigDelegate
  val solrRequestHandler: String by servletConfigDelegate

  val defaultBasePath: String by servletConfigDelegate
  val tileMapResourcePathField: String by servletConfigDelegate
  val imageZXYPathsField: String by servletConfigDelegate

  lateinit var solrClient: SolrClient

  val TILE_WIDTH_HEIGHT = 256
  val blankPngBytes: ByteArray = mergeImages(emptyList()).let { bufferedImageToPngByteArray(it) }

  override fun init() {
    super.init()
    val solrBaseUrl = getInitParameter("solrBaseUrl") ?: "http://localhost:8983/solr"
    solrClient = HttpSolrClient.Builder(solrBaseUrl).build()
  }

  override fun destroy() {
    solrClient.close()
  }

  override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
    // note: assume coordinates are all positive integers
    val pathMatch = Regex("/tile/(\\d+)/(\\d+)/(\\d+)\\.png").matchEntire(req.pathInfo ?: "")
    if (pathMatch == null) { req.servletContext.getFilterRegistration("SolrRequestFilter")
      resp.sendError(400, "Request didn't match expected pattern")
      return
    }
    val z = pathMatch.groupValues[1].toInt()
    val x = pathMatch.groupValues[2].toInt()
    val y = pathMatch.groupValues[3].toInt()

    // query Solr for docs with raster images for this tile.
    //    Sort by increasing

    val solrParams = ModifiableSolrParams(LinkedHashMap(req.parameterMap))
//    req.parameterMap.forEach { entry -> solrParams.set(entry.key, *entry.value) }
    solrParams.set("qt", solrRequestHandler)

    //TODO should we get via docValues? what if we need much more?
    solrParams.set("fl", "$tileMapResourcePathField")

    val zxy = "$z/$x/$y"
    solrParams.add("fq", "{!field f=$imageZXYPathsField cache=false}$zxy")

    //TODO pass-through HTTP caching headers;
    val solrResp = solrClient.query(solrCollection, solrParams)

    val basePath = Paths.get(defaultBasePath)
    val tilePaths = solrResp.results.map { doc ->
      resolveTileFile(basePath, doc[tileMapResourcePathField] as String, zxy, "png")
    }

    resp.contentType = "image/png"
    //TODO set cache header

    // note: don't close Servlet's OutputStream
    if (tilePaths.isEmpty()) {
      // Return an empty tile
      resp.setContentLength(blankPngBytes.size)
      resp.outputStream.write(blankPngBytes)

    } else if (tilePaths.size == 1) {
      // Simply return it
      val tilePath = tilePaths.first()
      resp.setContentLengthLong(Files.size(tilePath))
      Files.newInputStream(tilePath).use { inputStream ->
        inputStream.copyTo(resp.outputStream, 64*1024)
      }

    } else {
      // More than one image
      val bufferedImage = mergeImages(tilePaths)
      //ImageIO.write(bufferedImage, "png", resp.outputStream)
      val bytes = bufferedImageToPngByteArray(bufferedImage)
      resp.setContentLength(bytes.size)
      resp.outputStream.write(bytes)
    }

  }

  private fun resolveTileFile(basePath: Path, tileRsrcRelPath: String, zxy: String, extension: String): Path {
    var rsrcPath = basePath.resolve(tileRsrcRelPath)
    if (rsrcPath.fileName.endsWith(".xml")) {
      rsrcPath = rsrcPath.parent
    }
    return rsrcPath.resolve("$zxy.$extension").apply {
      check(Files.exists(this))
    }
  }

  private fun mergeImages(tilePaths: List<Path>): BufferedImage {
    // Create a buffered image in which to draw
    val bufferedImage = BufferedImage(TILE_WIDTH_HEIGHT, TILE_WIDTH_HEIGHT, BufferedImage.TYPE_INT_ARGB)

    // Create a graphics contents on the buffered image
    val g2d = bufferedImage.createGraphics()

    // Fill with transparency
    g2d.composite = AlphaComposite.Clear
    g2d.fillRect(0, 0, bufferedImage.width, bufferedImage.height)
    g2d.composite = AlphaComposite.SrcOver // next images write over

    // Overlay the images in-order
    for (tilePath in tilePaths) {
      val nextBufImage = ImageIO.read(tilePath.toFile())
      g2d.drawRenderedImage(nextBufImage, null)//no transformation
    }

    g2d.dispose()
    return bufferedImage
  }

  private fun bufferedImageToPngByteArray(bufferedImage: BufferedImage): ByteArray {
    val outputStream = ByteArrayOutputStream(256*1024)
    ImageIO.write(bufferedImage, "png", outputStream)
    return outputStream.toByteArray()
  }
}

