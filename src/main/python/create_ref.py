#!/usr/bin/env python
# -*- coding: utf-8 -*-

import geopandas as gpd
import pandas as pd
from matsim.scenariogen.data import run_create_ref_data
from matsim.scenariogen.data.preparation import calc_needed_short_distance_trips

CRS = "EPSG:25832"


def person_filter(df):
    df = gpd.GeoDataFrame(df, geometry=gpd.GeoSeries.from_wkt(df.geom, crs="EPSG:4326").to_crs(CRS))
    df = gpd.sjoin(df, region, how="inner", predicate="intersects")

    return df[df.present_on_day & (df.reporting_day <= 5)]


def trip_filter(df):
    # Other modes are ignored in the total share
    return df[df.main_mode != "other"]


if __name__ == "__main__":
    region = gpd.read_file("../../../../shared-svn/projects/DiTriMo/data/shp/lausitz.shp").to_crs(CRS)

    persons, trips, share = run_create_ref_data.create("/Volumes/Untitled/B3_Lokal-Datensatzpaket/CSV",
                                                       person_filter, trip_filter,
                                                       run_create_ref_data.InvalidHandling.REMOVE_TRIPS)

    print("Filtered %s persons" % len(persons))
    print("Filtered %s trips" % len(trips))

    print(share)

    # Simulated trips
    sim_persons = pd.read_csv("../../../output/output-lausitz-100pct/lausitz-100pct.output_persons.csv.gz",
                              delimiter=";", dtype={"person": "str"})
    sim_persons = sim_persons[sim_persons.subpopulation == "person"]
    sim_persons = gpd.GeoDataFrame(sim_persons,
                                   geometry=gpd.points_from_xy(sim_persons.home_x, sim_persons.home_y)).set_crs(CRS)

    sim_persons = gpd.sjoin(sim_persons, region, how="inner", predicate="intersects")

    sim = pd.read_csv("../../../output/output-lausitz-100pct/lausitz-100pct.output_trips.csv.gz",
                      delimiter=";", dtype={"person": "str"})

    sim = pd.merge(sim, sim_persons, how="inner", left_on="person", right_on="person", validate="many_to_one")

    share, add_trips = calc_needed_short_distance_trips(trips, sim, max_dist=1500)
    print("Short distance trip missing: ", add_trips)
