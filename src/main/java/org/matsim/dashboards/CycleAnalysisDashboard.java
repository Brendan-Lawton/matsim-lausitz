package org.matsim.dashboards;

import org.matsim.application.analysis.traffic.TrafficAnalysis;
import org.matsim.run.analysis.CycleAnalysis;
import org.matsim.simwrapper.Dashboard;
import org.matsim.simwrapper.Header;
import org.matsim.simwrapper.Layout;
import org.matsim.simwrapper.viz.*;
import tech.tablesaw.plotly.traces.BarTrace;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows information about an optional policy case, which implements cycle highways in Leipzig.
 * It also compares the agents and their trips using the cycle highways with their respective trips in the base case.
 */
public class CycleAnalysisDashboard implements Dashboard {
	private static final String SHARE = "share";
	private static final String ABSOLUTE = "Count [trip_id]";
	private static final String INCOME_GROUP = "income_group";
	private static final String CRS = "EPSG:25832";
	private static final String SOURCE = "source";
	private static final String MAIN_MODE = "main_mode";
	private static final String TRAFFIC = "bike_traffic";


	public CycleAnalysisDashboard(String outputDirectory) {
	}

	@Override
	public void configure(Header header, Layout layout) {
		header.title = "Bike Dashboard";
		header.description = "Shows statistics about agents, who used bike as their main mode.";

		String basePath = "";
//		String shp = "/home/brendan/git/matsim-lausitz/output/output-lausitz-1pct/lausitz-1pct.output_network.xml.gz";
		String[] args = new ArrayList<>(List.of("--base-path", basePath)).toArray(new String[0]);
		CycleAnalysis CycleAnalysis = new CycleAnalysis();
		layout.row("first")
			.el(Tile.class, (viz, data) -> {
				viz.dataset = data.compute(CycleAnalysis.class, "mean_travel_stats.csv");
				viz.height = 0.1;
			});
		layout.row("second")
			.el(Tile.class, (viz, data) -> {
				viz.dataset = data.compute(CycleAnalysis.class, "cyclist_demo_stats.csv");
				viz.height = 0.1;
			});

		layout.row("modalSplit")
			.el(Plotly.class, (viz, data) -> {
				viz.title = "Modal split";

				viz.layout = tech.tablesaw.plotly.components.Layout.builder()
					.barMode(tech.tablesaw.plotly.components.Layout.BarMode.STACK)
					.build();

				Plotly.DataSet ds = viz.addDataset(data.compute(CycleAnalysis.class, "mode_share.csv"))
					.constant(SOURCE, "Base case")
					.aggregate(List.of(MAIN_MODE), SHARE, Plotly.AggrFunc.SUM);

//				Plotly.DataSet dsBase = viz.addDataset(data.compute(CycleAnalysis.class, "mode_share_base.csv", args))
//					.constant(SOURCE, "Base")
//					.aggregate(List.of(MAIN_MODE), SHARE, Plotly.AggrFunc.SUM);

				viz.mergeDatasets = true;

				viz.addTrace(BarTrace.builder(Plotly.OBJ_INPUT, Plotly.INPUT).orientation(BarTrace.Orientation.HORIZONTAL).build(),
					ds.mapping()
						.name(MAIN_MODE)
						.y(SOURCE)
						.x(SHARE)
				);
//				viz.addTrace(BarTrace.builder(Plotly.OBJ_INPUT, Plotly.INPUT).orientation(BarTrace.Orientation.HORIZONTAL).build(),
//					dsBase.mapping()
//						.name(MAIN_MODE)
//						.y(SOURCE)
//						.x(SHARE)
//				);
			});
//			.el(Sankey.class, (viz, data) -> {
//				viz.title = "Mode shift (to bike)";
//				viz.width = 1.5d;
//				viz.description = "by main mode. Compares base case output with output after the last iteration";
//				viz.csv = data.compute(CycleAnalysis.class, "mode_shift.csv", args);
//			});

//		layout.row("locations")
//			.el(Hexagons.class, (viz, data) -> {
//
//				viz.title = "Cycle highway trips - Origins";
//				viz.center = data.context().getCenter();
//				viz.zoom = data.context().mapZoomLevel;
//				viz.height = 7.5;
//				viz.width = 2.0;
//				viz.file = data.compute(CycleAnalysis.class, "cycle_highway_agents_trip_start_end.csv");
//				viz.projection = CRS;
//				viz.addAggregation("trip origins", "person", "start_x", "start_y");
//			})
//			.el(Hexagons.class, (viz, data) -> {
//				viz.title = "Cycle highway trips - Destinations";
//				viz.center = data.context().getCenter();
//				viz.zoom = data.context().mapZoomLevel;
//				viz.height = 7.5;
//				viz.width = 2.0;
//				viz.file = data.compute(CycleAnalysis.class, "cycle_highway_agents_trip_start_end.csv");
//				viz.projection = CRS;
//				viz.addAggregation("trip destinations", "person", "end_x", "end_y");
//			});
//			.el(MapPlot.class, (viz, data) -> {
//				viz.title = "Cycle highways";
//				viz.center = data.context().getCenter();
//				viz.zoom = data.context().mapZoomLevel;
//				viz.height = 7.5;
//				viz.width = 2.0;
//				viz.setShape(data.compute(CycleAnalysis.class, "cycle_highways.shp"), "id");
//			});

		createIncomeLayouts(layout, args);

		layout.row("Avg. Speed")
			.el(MapPlot.class, (viz, data) -> {

				viz.title = "Simulated Average Speed per Link by bike";
				viz.center = data.context().getCenter();
				viz.zoom = data.context().mapZoomLevel;
				viz.height = 7.5;
				viz.width = 2.0;
				viz.setShape("network.avro", "id");
				viz.addDataset(TRAFFIC, data.compute(TrafficAnalysis.class, "traffic_stats_by_link_daily.csv"));
//				viz.display.lineColor.dataset = TRAFFIC;
				viz.display.lineColor.columnName = "avg_speed";
				viz.display.lineColor.join = "link_id";
				viz.display.lineColor.setColorRamp(ColorScheme.RdYlBu, 5, true);

//				viz.display.lineWidth.dataset = TRAFFIC;
				viz.display.lineWidth.columnName = "avg_speed";
				viz.display.lineWidth.scaleFactor = 5000d;
				viz.display.lineWidth.join = "link_id";

			});

	}

	private static void createIncomeLayouts(Layout layout, String[] args) {
		layout.row("income")
			.el(Bar.class, (viz, data) -> {
				viz.title = "Bike users per income group - Policy";
				viz.stacked = false;
				viz.dataset = data.compute(CycleAnalysis.class, "bike_income_groups.csv");
				viz.x = INCOME_GROUP;
				viz.xAxisName = "Income Group";
				viz.yAxisName = "Share Agents";
				viz.columns = List.of(SHARE);
			});

//		layout.row("incomeBase")
//			.el(Bar.class, (viz, data) -> {
//				viz.title = "Bike users per income group - Base";
//				viz.stacked = false;
//				viz.dataset = data.compute(CycleAnalysis.class, "bike_income_groups_base.csv", args);
//				viz.x = INCOME_GROUP;
//				viz.xAxisName = INCOME_GROUP;
//				viz.yAxisName = SHARE;
//				viz.columns = List.of(SHARE);
//			})
//			.el(Bar.class, (viz, data) -> {
//				viz.title = "Bike users per income group - Base";
//				viz.stacked = false;
//				viz.dataset = data.compute(CycleAnalysis.class, basePath + "bike_income_groups_base.csv", args);
//				viz.x = INCOME_GROUP;
//				viz.xAxisName = INCOME_GROUP;
//				viz.yAxisName = ABSOLUTE;
//				viz.columns = List.of(ABSOLUTE);
//			});

//		layout.row("incomeBaseLeipzig")
//			.el(Bar.class, (viz, data) -> {
//				viz.title = "Leipzig residents per income group - Base";
//				viz.stacked = false;
//				viz.dataset = data.compute(CycleAnalysis.class, "allModes_income_groups_base_leipzig.csv", args);
//				viz.x = INCOME_GROUP;
//				viz.xAxisName = INCOME_GROUP;
//				viz.yAxisName = SHARE;
//				viz.columns = List.of(SHARE);
//			})
//			.el(Bar.class, (viz, data) -> {
//				viz.title = "Leipzig residents (bike) per income group - Base";
//				viz.stacked = false;
//				viz.dataset = data.compute(CycleAnalysis.class, "bike_income_groups_base_leipzig.csv", args);
//				viz.x = INCOME_GROUP;
//				viz.xAxisName = INCOME_GROUP;
//				viz.yAxisName = SHARE;
//				viz.columns = List.of(SHARE);
//			});
//			.el(Bar.class, (viz, data) -> {
//				viz.title = "Leipzig residents (car) per income group - Base";
//				viz.stacked = false;
//				viz.dataset = data.compute(CycleAnalysis.class, "car_income_groups_base_leipzig.csv", args);
//				viz.x = INCOME_GROUP;
//				viz.xAxisName = INCOME_GROUP;
//				viz.yAxisName = SHARE;
//				viz.columns = List.of(SHARE);
//			});
//
//		layout.row("incomeBaseLeipzig2")
//			.el(Bar.class, (viz, data) -> {
//				viz.title = "Leipzig residents (pt) per income group - Base";
//				viz.stacked = false;
//				viz.dataset = data.compute(CycleAnalysis.class, "pt_income_groups_base_leipzig.csv", args);
//				viz.x = INCOME_GROUP;
//				viz.xAxisName = INCOME_GROUP;
//				viz.yAxisName = SHARE;
//				viz.columns = List.of(SHARE);
//			})
//			.el(Bar.class, (viz, data) -> {
//				viz.title = "Leipzig residents (walk) per income group - Base";
//				viz.stacked = false;
//				viz.dataset = data.compute(CycleAnalysis.class, "walk_income_groups_base_leipzig.csv", args);
//				viz.x = INCOME_GROUP;
//				viz.xAxisName = INCOME_GROUP;
//				viz.yAxisName = SHARE;
//				viz.columns = List.of(SHARE);
//			})
//			.el(Bar.class, (viz, data) -> {
//				viz.title = "Leipzig residents (ride) per income group - Base";
//				viz.stacked = false;
//				viz.dataset = data.compute(CycleAnalysis.class, "ride_income_groups_base_leipzig.csv", args);
//				viz.x = INCOME_GROUP;
//				viz.xAxisName = INCOME_GROUP;
//				viz.yAxisName = SHARE;
//				viz.columns = List.of(SHARE);
//			});
	}
}
