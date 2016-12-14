#!/bin/sh
set -u -e # best practice

url="http://localhost:8983/solr/mapserver/update/json/docs/?commitWithin=1000"

# Bruce's test data

curl -v -X POST "$url" -H 'Content-type:application/json' -d '[
{
  "id":"apollotempletest",
  "tileMapResourcePath":"apollotempletest"
},
{
  "id":"bth-east",
  "tileMapResourcePath":"bth-east"
},
{
  "id":"bth-west",
  "tileMapResourcePath":"bth-west"
},
{
  "id":"bz",
  "tileMapResourcePath":"bz"
},
{
  "id":"medium-warped",
  "tileMapResourcePath":"medium-warped"
},
{
  "id":"small-warped",
  "tileMapResourcePath":"small-warped"
}
]'