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
import org.apache.solr.client.solrj.embedded.JettyConfig
import org.apache.solr.client.solrj.embedded.JettySolrRunner
import org.apache.solr.client.solrj.impl.HttpSolrClient
import org.apache.solr.common.SolrInputDocument
import org.eclipse.jetty.servlet.ServletHolder
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TmsSolrServletTest() {

  companion object {
    const val testId1 = "small-warped"
    const val ZYX = "19/296692/322011" // found in our test image

    const val COLL = "mapserver"

    lateinit var jettyServer: JettySolrRunner
    lateinit var TMS_URL_BASE: String // e.g. http://localhost:8983/tms
    lateinit var solrClient: SolrClient

    @ClassRule @JvmField
    val tmpDir = TemporaryFolder()

    @BeforeClass() @JvmStatic
    fun beforeTests() {
      // referenced by solrconfig:
      val tmsBasePath = "src/test/resources/" // for the images
      System.setProperty("tms_defaultBasePath", tmsBasePath)
      System.setProperty("solr.data.dir", tmpDir.newFolder("solr_data_dir").absolutePath)
      System.setProperty("java.awt.headless", "true")

      val tmsServletHolder = ServletHolder(TmsSolrServlet())
      tmsServletHolder.initParameters = mapOf(
              "solrCollection" to COLL,
              "solrRequestHandler" to "/tms",
              "defaultBasePath" to tmsBasePath,
              "imageZXYPathsField" to "imageZXYPaths",
              "tileMapResourcePathField" to "tileMapResourcePath")
      val jettyConfig = JettyConfig.Builder().withServlet(tmsServletHolder, "/tms/*").setPort(8983).build()
      jettyServer = JettySolrRunner("solrHome/", jettyConfig)
      jettyServer.start()
      //TODO jettyServer.baseUrl.toString()  why can't this resolve the host?
      TMS_URL_BASE = "http://localhost:8983/solr/tms" // yes weird that includes "tms" but fine
      solrClient = HttpSolrClient.Builder("http://localhost:8983/solr").build()

      // add some test data:
      solrClient.add(COLL, SolrInputDocument("id", testId1,
              "tileMapResourcePath", testId1))
      solrClient.commit(COLL)
    }

    @AfterClass
    @JvmStatic
    fun afterTests() {
      solrClient.close()
      jettyServer.stop()
    }

  }

  @Test
  fun testNotFound() {
    queryImage("$TMS_URL_BASE/tile/00/00/00.png")//bogus img
    queryImage("$TMS_URL_BASE/tile/$ZYX.png?q=id:nonexistent")
  }

  @Test
  fun testFoundOne() {
    queryImage("$TMS_URL_BASE/tile/$ZYX.png")
    queryImage("$TMS_URL_BASE/tile/$ZYX.png?id:$testId1")
  }

  @Test
  fun testFoundTwo() {
    val doc2Path = Paths.get("data/medium-warped/")
    assumeTrue("data/ is found", Files.exists(doc2Path))
    // add some test data:
    val id2 = "medium-warped"
    solrClient.add(COLL, SolrInputDocument("id", id2,
            "tileMapResourcePath", doc2Path.toAbsolutePath().toString()))
    solrClient.commit(COLL)
    try {

      queryImage("$TMS_URL_BASE/tile/$ZYX.png")
      //Thread.sleep(1000*60*10)
    } finally {
      solrClient.deleteByQuery(COLL, "id:$id2")
      solrClient.commit(COLL)
    }

  }

  private fun queryImage(url: String, withConn: (HttpURLConnection) -> Unit = ::assertRespHasImage) {
    val urlConn = URL(url).openConnection() as HttpURLConnection
    urlConn.connect()
    try {
      withConn(urlConn)
    } finally {
      urlConn.disconnect()
    }
  }

  @Test
  fun testCacheEtag() {
    val etagRef = AtomicReference<String>()
    val url = "$TMS_URL_BASE/tile/$ZYX.png"
    queryImage(url) { urlConn -> etagRef.set( urlConn.getHeaderField("ETag") ) }
    assertNotNull(etagRef.get())

    // should get 304 code
    val urlConn = URL(url).openConnection() as HttpURLConnection
    urlConn.setRequestProperty("If-None-Match", etagRef.get())
    urlConn.connect()
    try {
      assertEquals(304, urlConn.responseCode)
      assertTrue(urlConn.getHeaderField("Cache-Control") != null)
    } finally {
      urlConn.disconnect()
    }
  }
}

private fun assertRespHasImage(urlConn: HttpURLConnection) {
  //out.println(urlConn.headerFields)
  assertEquals("image/png", urlConn.contentType)
  assertEquals(200, urlConn.responseCode)
}
