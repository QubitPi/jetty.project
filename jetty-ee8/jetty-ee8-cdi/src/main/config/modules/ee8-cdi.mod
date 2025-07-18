# DO NOT EDIT THIS FILE - See: https://jetty.org/docs/

[description]
Provides integration of CDI within webapp to Jetty container object lifecycles.
This module does not provide CDI, but configures jetty to support various
integration modes with a CDI implementation on the webapp classpath.
CDI integration modes can be selected per webapp with the "org.eclipse.jetty.ee8.cdi"
init parameter or defaults to the mode set by the "org.eclipse.jetty.ee8.cdi" server
attribute (which is initialised from the "jetty.cdi.mode" start property).
Supported modes are:
CdiSpiDecorator     - Jetty will call the CDI SPI within the webapp to decorate
                      objects (default).
CdiDecoratingLister - The webapp may register a decorator on the context attribute
                      "org.eclipse.jetty.ee8.cdi.decorator".
[environment]
ee8

[tag]
cdi

[provides]
cdi

[depend]
deploy

[xml]
etc/cdi/jetty-ee8-cdi.xml

[lib]
lib/jetty-ee8-cdi-${jetty.version}.jar
