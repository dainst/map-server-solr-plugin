This is a plugin to Apache Solr that helps it act as a Tile Map Service (TMS)
for some basic use-case.  Specifically it exposes a TMS service that also
accepts Solr query parameters.  For example:

    http://localhost:8983/solr/tms/tile/19/296692/322011.png?q=text:foo

Since standard TMS clients don't know to pass query parameters after the image,
it will require some amount of custom integration.

This plugin is primarily composed of two components:

* A Solr URP: SpatialMetadataURP
* A Java Servlet that talks to Solr

Assumptions/Limitations:
========================

* Each Solr document that points to a raster image must point to a local
file system path.
* All raster images must be pre-tiled in the same manner.  tilemapresource.xml
is required. If it's values don't fit the expectations of the plugin
provided here, you'll get an error to let you know.
 *  TODO specify assumptions
* The TMS servlet shares the same file system with Solr (i.e. is on
the same machine).
* Solr 6.x


Build, Deploy, Configure, Run
=============================

Requirements: Java 8, Maven

    mvn package

That produces a '.jar' file in target, and it gathers it's dependencies
(apart from SolrJ) into target/dependency/.  Notably this includes 2 Kotlin jars.

URP Installation:

* copy target/map-server*.jar and target/dependency/*.jar into
  SOLR_INSTALL_DIR/server/solr-webapp/webapp/WEB-INF/lib/  These
  jar's won't be there already, but if you upgrade this software then
  be careful that you remove any older versions first.

* schema: Most of the fields in the schema.xml provided here
 solrHome/mapserver/conf/schema.xml are required.  See URP config...

* solrconfig: Notice the URP SpatialMetadataURPFactory.  There are
several ways to go about using an URP; the approach in
solrHome/mapserver/conf/solrconfig.xml is least error-prone I think.
Notice that it has many parameters that reference field names in the schema.
This underscores that you can name the fields what you want.


TMS Servlet installation (Embed with Solr Jetty):

*  assume the URP instructions are followed above, especially putting the
jar files into place.  Now edit
SOLR_INSTALL_DIR/server/solr-webapp/webapp/WEB-INF/web.xml and insert the following XML snippet
before the first mention of any existing <servlet> tag:

````
  <servlet>
    <servlet-name>TMS</servlet-name>
    <servlet-class>io.github.codarchlab.mapserversolrplugin.TmsSolrServlet</servlet-class>
    <!--<init-param>
      <param-name>defaultBasePath</param-name>
      <param-value>/Path/to/tms/</param-value>
    </init-param>-->
    <init-param>
      <param-name>solrCollection</param-name>
      <param-value>mapserver</param-value>
    </init-param>
    <init-param>
      <param-name>solrRequestHandler</param-name>
      <param-value>/tms</param-value>
    </init-param>
    <init-param>
      <param-name>tileMapResourcePathField</param-name>
      <param-value>tileMapResourcePath</param-value>
    </init-param>
    <init-param>
      <param-name>imageZXYPathsField</param-name>
      <param-value>imageZXYPaths</param-value>
    </init-param>
  </servlet>
  <servlet-mapping>
    <servlet-name>TMS</servlet-name>
    <url-pattern>/tms/*</url-pattern>
  </servlet-mapping>
````

Some of those init-params are the same as those found in the URP and thus
identify certain fields.  For convenience, all those init-params are
resolveable as Java "system properties" with a "tms." extension, such
as `-Dtms.defaultBasePath=/Path/to/tms/` And that parameter in particular
is useful to set as-such.

* (recommended) dedicated Solr request handler.  Instead of sending
everything through Solr /select, it's highly recommended to have a dedicated
Solr RequestHandler.  In this example, it's `/tms` (coincidentally the
same string as where this servlet is mapped to.  Look in
solrHome/mapserver/conf/solrconfig.xml and you'll see a /tms RequestHandler
along with some defaults that could be changed.  In particular, it's important
to order the results by biggest to largest to minimize occlusion.

Running Solr:

In the below example, I've taken Solr 6.1, duplicated the "server" directory as "server-mapserver" and then
I followed the installation instructions above into there so as not to disturb use of other Solr setups.  It's
very much optional.  This is where the "-d serverdir" comes into place. I'm also pointing
to the "Solr home directory" of this project for convenience.  Again, you very well might
not do this in a production setting.  The "java.awt.headless" property avoids issues with
java interfering with the OS desktop GUI environment, which might even cause a failure
in certain Unix setups.  The "-f" puts Solr in the foreground to observe for errors.  Again,
this is at your prerogative.  The "-Dtms.defaultBasePath" points to the root directory that all
Solr document image paths are relative to.

    bin/solr start -f -d server-mapserver/ \
      -a "-Dtms.defaultBasePath=/Volumes/HD1500/Archeology_Tiles/ -Djava.awt.headless=true" \
      -s /SmileyDev/Proj/SolrMapServer/solrHome

TMS image requests
==================

This shows an example with no specific Solr parameters.  Notice that
it's hosted within Solr's webapp: port 8983 context /solr.  The servlet
is registered to handle /tms/* after that.  Internally this servlet assumes
Solr is running on the same machine in the default setup (which you
probably shouldn't change any way).  Simply add Solr query parameters
to this to filter the images further by whatever Solr query you can
craft.

        http://localhost:8983/solr/tms/tile/19/296692/322011.png

TODO
====
 * See above limitations
 * support URL "matrix parameters" as an alternative to query params, to
 thus make this usable by standard TMS clients?