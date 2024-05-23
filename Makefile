
N := lausitz
V := v1.0
CRS := EPSG:25832
JAR := matsim-$(N)-*.jar

osmosis := osmosis/bin/osmosis
germany := ../shared-svn/projects/matsim-germany
shared := ../shared-svn/projects/DiTriMo
lausitz := ../public-svn/matsim/scenarios/countries/de/lausitz/lausitz-$V

MEMORY ?= 20G
SUMO_HOME ?= $(abspath ../../sumo-1.18.0/)
NETWORK := $(germany)/maps/germany-230101.osm.pbf

# Scenario creation tool
sc := java -Xmx$(MEMORY) -XX:+UseParallelGC -jar $(JAR)

.PHONY: prepare

# Bast count data
input/2019_A_S.zip:
	curl https://www.bast.de/videos/2019_A_S.zip -o $@

input/2019_B_S.zip:
	curl https://www.bast.de/videos/2019_B_S.zip -o $@

input/Jawe2019.csv:
	curl "https://www.bast.de/DE/Verkehrstechnik/Fachthemen/v2-verkehrszaehlung/Daten/2019_1/Jawe2019.csv?view=renderTcDataExportCSV&cms_strTyp=A" -o $@

input/network.osm: $(NETWORK)

#	retrieve detailed network (see param highway) from OSM
	$(osmosis) --rb file=$<\
	 --tf accept-ways bicycle=yes highway=motorway,motorway_link,trunk,trunk_link,primary,primary_link,secondary_link,secondary,tertiary,motorway_junction,residential,unclassified,living_street\
	 --bounding-polygon file="$(shared)/data/cottbus.poly"\
	 --used-node --wb input/network-detailed.osm.pbf

	# This includes residential as well, since multiple cities are covered by the study area
	#	retrieve coarse network (see param highway) from OSM
	$(osmosis) --rb file=$<\
	 --tf accept-ways highway=motorway,motorway_link,trunk,trunk_link,primary,primary_link,secondary_link,secondary,tertiary,motorway_junction,residential\
	 --bounding-polygon file="$(shared)/data/lausitz.poly"\
	 --used-node --wb input/network-coarse.osm.pbf

	#	retrieve germany wide network (see param highway) from OSM
	$(osmosis) --rb file=$<\
	 --tf accept-ways highway=motorway,motorway_link,motorway_junction,trunk,trunk_link,primary,primary_link\
	 --used-node --wb input/network-germany.osm.pbf

#	put the 3 above networks together and remove raia
	$(osmosis) --rb file=input/network-germany.osm.pbf --rb file=input/network-coarse.osm.pbf --rb file=input/network-detailed.osm.pbf\
  	 --merge --merge\
  	 --tag-transform file=input/remove-railway.xml\
  	 --wx $@

	rm input/network-detailed.osm.pbf
	rm input/network-coarse.osm.pbf
	rm input/network-germany.osm.pbf


input/sumo.net.xml: input/network.osm

#	create sumo network from osm network
	$(SUMO_HOME)/bin/netconvert --geometry.remove --ramps.guess --ramps.no-split\
#	roadTypes are taken either from the general file "osmNetconvert.typ.xml" or from the german one "osmNetconvertUrbanDe.ty.xml"
	 --type-files $(SUMO_HOME)/data/typemap/osmNetconvert.typ.xml,$(SUMO_HOME)/data/typemap/osmNetconvertUrbanDe.typ.xml\
	 --tls.guess-signals true --tls.discard-simple --tls.join --tls.default-type actuated\
	 --junctions.join --junctions.corner-detail 5\
	 --roundabouts.guess --remove-edges.isolated\
	 --no-internal-links --keep-edges.by-vclass passenger,bicycle\
	 --remove-edges.by-vclass hov,tram,rail,rail_urban,rail_fast,pedestrian\
	 --output.original-names --output.street-names\
	 --proj "+proj=utm +zone=32 +ellps=GRS80 +towgs84=0,0,0,0,0,0,0 +units=m +no_defs"\
	 --osm-files $< -o=$@


# transform sumo network to matsim network and clean it afterwards
# free-speed-factor 0.75 (standard is 0.9): see VSP WP 24-08. lausitz is mix between rural and city (~0.7 - 0.8)
input/$V/$N-$V-network.xml.gz: input/sumo.net.xml
	$(sc) prepare network-from-sumo $< --output $@ --free-speed-factor 0.75
	$(sc) prepare clean-network $@ --output $@ --modes car --modes bike


#add pt to network from german wide gtfs, but only for area of shp file
input/$V/$N-$V-network-with-pt.xml.gz: input/$V/$N-$V-network.xml.gz
	$(sc) prepare transit-from-gtfs --network $<\
	 --output=input/$V\
	 --name $N-$V --date "2023-01-11" --target-crs $(CRS) \
	 $(shared)/data/gtfs/20230113_regio.zip\
	 $(shared)/data/gtfs/20230113_train_short.zip\
	 $(shared)/data/gtfs/20230113_train_long.zip\
	 --prefix regio_,short_,long_\
	 --shp $(shared)/data/network-area/network-area.shp\
	 --shp $(shared)/data/network-area/network-area.shp\
	 --shp $(shared)/data/germany-area/germany-area.shp\

# extract lausitz long haul freight traffic trips from german wide file
input/plans-longHaulFreight.xml.gz: input/$V/$N-$V-network.xml.gz
	$(sc) prepare extract-freight-trips ../public-svn/matsim/scenarios/countries/de/german-wide-freight/v2/german_freight.25pct.plans.xml.gz\
	 --network ../public-svn/matsim/scenarios/countries/de/german-wide-freight/v2/germany-europe-network.xml.gz\
	 --input-crs $(CRS)\
	 --target-crs $(CRS)\
	 --shp input/shp/lausitz.shp --shp-crs $(CRS)\
	 --cut-on-boundary\
	 --output $@

# trajectory-to-plans formerly was a collection of methods to prepare a given population
# now, most of the functions of this class do have their own class (downsample, splitduration types...)
# it basically only transforms the old attribute format to the new one
input/$V/prepare-100pct.plans.xml.gz:
	$(sc) prepare trajectory-to-plans\
	 --name prepare --sample-size 1 --output input/$V\
	 --max-typical-duration 0\
	 --population $(shared)/matsim-input-files/senozon/20230111_teilmodell_lausitz/population.xml.gz\
	 --attributes  $(shared)/matsim-input-files/senozon/20230111_teilmodell_lausitz/additionalPersonAttributes.xml.gz

	# resolve senozon aggregated grid coords (activities): distribute them based on landuse.shp
	$(sc) prepare resolve-grid-coords\
	 input/$V/prepare-100pct.plans.xml.gz\
	 --input-crs $(CRS)\
	 --grid-resolution 300\
	 --landuse $(germany)/landuse/landuse.shp\
	 --output $@

input/$V/$N-$V-100pct.plans-initial.xml.gz: input/plans-longHaulFreight.xml.gz input/$V/prepare-100pct.plans.xml.gz

#	generate some short distance trips, which in senozon data generally are missing
# trip range 700m because:
# when adding 1km trips (default value), too many trips of bin 1km-2km were also added.
#the range value is beeline, so the trip distance (routed) often is higher than 1km
	$(sc) prepare generate-short-distance-trips\
 	 --population input/$V/prepare-100pct.plans.xml.gz\
 	 --input-crs $(CRS)\
	 --shp input/shp/lausitz.shp --shp-crs $(CRS)\
	 --range 700\
 	 --num-trips 324430

#	adapt coords of activitzies in the wider network such that they are closer to a link
# 	such that agents do not have to walk as far as before
	$(sc) prepare adjust-activity-to-link-distances input/$V/prepare-100pct.plans-with-trips.xml.gz\
	 --shp input/shp/lausitz.shp --shp-crs $(CRS)\
     --scale 1.15\
     --input-crs $(CRS)\
     --network input/$V/$N-$V-network.xml.gz\
     --output input/$V/prepare-100pct.plans-adj.xml.gz

#	change modes in subtours with chain based AND non-chain based by choosing mode for subtour randomly
	$(sc) prepare fix-subtour-modes --coord-dist 100 --input input/$V/prepare-100pct.plans-adj.xml.gz --output $@

#	set car availability for agents below 18 to false, standardize some person attrs, set home coords, set income
	$(sc) prepare population $@ --output $@

#	split activity types to type_duration for the scoring to take into account the typical duration
	$(sc) prepare split-activity-types-duration\
		--input $@\
		--exclude commercial_start,commercial_end,freight_start,freight_end\
		--output $@

#	merge person and freight pops
	$(sc) prepare merge-populations $@ $< --output $@

	$(sc) prepare downsample-population $@\
    	 --sample-size 1\
    	 --samples 0.25 0.1 0.01\

# create matsim counts file
input/$V/$N-$V-counts-bast.xml.gz: input/2019_A_S.zip input/2019_B_S.zip input/Jawe2019.csv input/$V/$N-$V-network-with-pt.xml.gz

	$(sc) prepare counts-from-bast\
		--network input/$V/$N-$V-network-with-pt.xml.gz\
		--motorway-data input/2019_A_S.zip\
		--primary-data input/2019_B_S.zip\
		--station-data input/Jawe2019.csv\
		--year 2019\
		--shp input/shp/lausitz.shp --shp-crs $(CRS)\
		--output $@

check: input/$V/$N-$V-100pct.plans-initial.xml.gz
	#commuter analysis, still TODO
	$(sc) analysis commuter\
	 --population $<\
 	 --input-crs $(CRS)\
	 --shp $(germany)/vg5000/vg5000_ebenen_0101/VG5000_GEM.shp\
	 --attr ARS\
	 --output input/$V/$N-$V-commuter.csv

	$(sc) analysis check-population $<\
 	 --input-crs $(CRS)\
	 --shp input/shp/lausitz.shp --shp-crs $(CRS)

# Aggregated target
prepare: input/$V/$N-$V-100pct.plans-initial.xml.gz input/$V/$N-$V-network-with-pt.xml.gz input/$V/$N-$V-counts-car-bast.xml.gz
	echo "Done"