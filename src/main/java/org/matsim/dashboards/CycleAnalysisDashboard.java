package org.matsim.dashboards;

import org.matsim.run.analysis.CycleAnalysis;
import org.matsim.simwrapper.*;
import org.matsim.simwrapper.Header;
import org.matsim.simwrapper.viz.*;
import tech.tablesaw.plotly.traces.BarTrace;

import java.util.ArrayList;
import java.util.List;

import static org.matsim.application.ApplicationUtils.globFile;

/**
 * Shows information about an optional policy case, which implements cycle highways in Leipzig.
 * It also compares the agents and their trips using the cycle highways with their respective trips in the base case.
 */
public class CycleAnalysisDashboard implements Dashboard {
	private final String basePath;
	private final String networkPath;
	private static final String SHARE = "share";
	private static final String ABSOLUTE = "Count [trip_id]";
	private static final String INCOME_GROUP = "income_group";
	private static final String CRS = "EPSG:25832";
	private static final String SOURCE = "source";
	private static final String MAIN_MODE = "main_mode";
	private static final String TRAFFIC = "bike_traffic";
	private static final String ALTITUDES = "link_altitudes";



	public CycleAnalysisDashboard(String basePath, String networkPath) {
		if (!basePath.endsWith("/")) {
			basePath += "/";
		}

		this.networkPath = networkPath;

		this.basePath = basePath;
	}

	@Override
	public void configure(Header header, Layout layout) {
		header.title = "Bike Dashboard";
		header.description = "Shows statistics about agents, who used bike as their main mode.";

//		String shp = "/home/brendan/git/matsim-lausitz/output/output-lausitz-1pct/lausitz-1pct.output_network.xml.gz";
		String[] args = new ArrayList<>(List.of("--base-path", basePath)).toArray(new String[0]);
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

				viz.mergeDatasets = true;

				viz.addTrace(BarTrace.builder(Plotly.OBJ_INPUT, Plotly.INPUT).orientation(BarTrace.Orientation.HORIZONTAL).build(),
					ds.mapping()
						.name(MAIN_MODE)
						.y(SOURCE)
						.x(SHARE)
				);
			});

		createDistrobutionDataLayouts(layout, "bike_income_groups.csv","Bike users per income group","Income Group","Share Agents", "income_group", "age_income_row");
		createDistrobutionDataLayouts(layout, "bike_age_groups.csv", "Bike users per age group", "Age Group","Share Agents", "age_group", "age_income_row");
		createDistrobutionDataLayouts(layout, "bike_traveled_distance_groups.csv", "Bike users per traveled distance", "Traveled Distance","Share Agents", "traveled_distance_group", "trav_distAndTime_row");
		createDistrobutionDataLayouts(layout, "bike_travel_time_groups.csv", "Bike users per traveled distance", "Travel Time","Share Agents", "trav_time_group", "trav_distAndTime_row");

		layout.row("Avg. Speed")
			.el(MapPlot.class, (viz, data) -> {

				viz.title = "Simulated Average Speed per Link by bike";
				viz.center = data.context().getCenter();
				viz.zoom = data.context().mapZoomLevel;
				viz.height = 7.5;
				viz.width = 2.0;
				viz.setShape(networkPath, "id");
				viz.addDataset(TRAFFIC, data.compute(CycleAnalysis.class, "traffic_stats_by_link_daily_bike.csv"));
				viz.display.lineColor.dataset = TRAFFIC;
				viz.display.lineColor.columnName = "avg_speed";
				viz.display.lineColor.join = "link_id";
				viz.display.lineColor.setColorRamp(ColorScheme.RdYlBu, 3, true);
				viz.display.lineWidth.dataset = TRAFFIC;
				viz.display.lineWidth.columnName = "avg_speed";
				viz.display.lineWidth.scaleFactor = 15d;
				viz.display.lineWidth.join = "link_id";

			});

		layout.row("Alt. Difference")
			.el(MapPlot.class, (viz, data) -> {

				viz.title = "Absolute Altitude Difference per Link (meters)";
				viz.center = data.context().getCenter();
				viz.zoom = data.context().mapZoomLevel;
				viz.height = 7.5;
				viz.width = 2.0;
				viz.setShape("metropole_network.avro", "id");
				viz.addDataset(ALTITUDES, data.compute(CycleAnalysis.class, "altitude_diff_by_link.csv"));
				viz.display.lineColor.dataset = TRAFFIC;
				viz.display.lineColor.columnName = "ALT_DIFF";
				viz.display.lineColor.join = "LINK_ID";
				viz.display.lineColor.setColorRamp(ColorScheme.RdYlBu, 10, true);

			});


	}



	private static void createDistrobutionDataLayouts(Layout layout, String data_file, String title, String xAxis, String yAxis, String group_name, String row_name) {
		layout.row(row_name)
			.el(Bar.class, (viz, data) -> {
				viz.title = title;
				viz.stacked = false;
				viz.dataset = data.compute(CycleAnalysis.class, data_file);
				viz.x = group_name;
				viz.xAxisName = xAxis;
				viz.yAxisName = yAxis;
				viz.columns = List.of(SHARE);
			});

	}
}
