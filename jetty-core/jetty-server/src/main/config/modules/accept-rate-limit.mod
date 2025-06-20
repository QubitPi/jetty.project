# DO NOT EDIT THIS FILE - See: https://jetty.org/docs/

[description]
Enables a server-wide accept rate limit.

[tags]
connector

[depend]
server

[xml]
etc/jetty-accept-rate-limit.xml

[ini-template]
#tag::documentation[]
## The limit for the rate of accepted connections.
#jetty.acceptratelimit.acceptRateLimit=1000

## The period of time to check for the rate of accepted
## connections to have returned within the limit.
#jetty.acceptratelimit.period=1000

## The unit of time for the period.
#jetty.acceptratelimit.units=MILLISECONDS
#end::documentation[]
