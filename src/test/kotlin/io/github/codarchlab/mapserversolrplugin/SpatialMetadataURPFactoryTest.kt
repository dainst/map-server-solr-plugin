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

import org.apache.solr.SolrTestCaseJ4

import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test

class SpatialMetadataURPFactoryTest : SolrTestCaseJ4() {

  companion object {
    @BeforeClass
    @JvmStatic
    fun beforeTests() {
      initCore("solrconfig.xml", "schema.xml", "solrHome", "mapserver")
    }
  }

  @Test
  fun test() {
    assertU(add(doc("id", "1",
            "tileMapResourcePath", javaClass.getResource("test-tilemapresource.xml").toString() )))
    assertU(commit())

    assertJQ(req("q", "id:1", "fl", "*"), 0.0,
            "/response/docs/[0]/id=='1'",
            "/response/docs/[0]/bbox=='ENVELOPE (23.720941, 23.724727, 37.976615, 37.973773)'",
            "/response/docs/[0]/point=='POINT (23.722834 37.975194)'",
            "/response/docs/[0]/area==8.475842268314418E-6",
            "/response/docs/[0]/tileFormatExtension=='png'",
            "/response/docs/[0]/zoomLevels/[0]==15",
            "/response/docs/[0]/zoomLevels/[5]==20"
    )
  }

}