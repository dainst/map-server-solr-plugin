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

import org.locationtech.spatial4j.context.SpatialContext
import org.locationtech.spatial4j.shape.Rectangle

val Spatial4jCTX: SpatialContext = SpatialContext.GEO

// Kotlin-ize for named-arg passing
inline fun SpatialContext.rect(minX:Double, maxX:Double, minY:Double, maxY:Double): Rectangle =
  this.shapeFactory.rect(minX, maxX, minY, maxY)

/**
 * Converts Slippy-Map Zoom,X,Y parameters to geodetic degrees.
 * Note that the orientation of 'y' is top-down whereas TMS spec is bottom-up.
 */
fun slippyMapTileToDegrees(zoom: Short, x: Int, y: Int): Rectangle {
  //http://wiki.openstreetmap.org/wiki/Slippy_map_tilenames#Java
  val POW2ZOOM = Math.pow(2.0, zoom.toDouble())
  fun tile2lon(x: Int): Double = x / POW2ZOOM * 360.0 - 180
  fun tile2lat(y: Int): Double = Math.toDegrees(
          Math.atan(Math.sinh(Math.PI - 2.0 * Math.PI * y.toDouble() / POW2ZOOM))
  )
  return Spatial4jCTX.rect(
          minX=tile2lon(x), maxX=tile2lon(x + 1),
          minY=tile2lat(y + 1), maxY=(tile2lat(y)))
}



/**
 * Converts Z,X,Y from TMS assuming "global-mercator" profile, and convert to a
 * decimal degrees rectangle (WGS84).

fun tmsGlobalMercatorToDegrees(z: Int, x: Int, y: Int): Rectangle {
  // http://wiki.osgeo.org/wiki/Tile_Map_Service_Specification
  // "global-mercator" profile:
  //    units-per-pixel meeting the following formula for any integral value of "n" greater than or
  //      equal to 0: units-per-pixel = 78271.516 / 2^n
  //    <Origin> of (-20037508.34, -20037508.34)
  val unitsPerPixel = 78271.516 / Math.pow(2.0, z.toDouble())
  val originX = -20037508.34
  val originY = originX

  val webmX = originX + x * unitsPerPixel
  val webmY = originY + y * unitsPerPixel


}*/