package org.matsim.dashboards;

import org.matsim.application.analysis.traffic.TrafficAnalysis;
import org.matsim.run.analysis.CycleAnalysis;
import org.matsim.simwrapper.*;
import org.matsim.simwrapper.Header;
import org.matsim.simwrapper.viz.*;
import tech.tablesaw.plotly.traces.BarTrace;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows information about an optional policy case, which implements cycle highways in Leipzig.
 * It also compares the agents and their trips using the cycle highways with their respective trips in the base case.
 */
public class CycleAnalysisDashboard implements Dashboard {
	private final String basePath;
	private static final String SHARE = "share";
	private static final String ABSOLUTE = "Count [trip_id]";
	private static final String INCOME_GROUP = "income_group";
	private static final String CRS = "EPSG:25832";
	private static final String SOURCE = "source";
	private static final String MAIN_MODE = "main_mode";
	private static final String TRAFFIC = "bike_traffic";


	public CycleAnalysisDashboard(String basePath) {
		if (!basePath.endsWith("/")) {
			basePath += "/";
		}

		this.basePath = basePath;
	}

	@Override
	public void configure(Header header, Layout layout) {
		header.title = "Bike Dashboard";
		header.description = "Shows statistics about agents, who used bike as their main mode.";

//		String shp = "/home/brendan/git/matsim-lausitz/output/output-lausitz-1pct/lausitz-1pct.output_network.xml.gz";
		String[] args = new ArrayList<>(List.of("--base-path", basePath)).toArray(new String[0]);
//		CycleAnalysis CycleAnalysis = new CycleAnalysis();
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
			});

		createIncomeLayouts(layout, args);

		layout.row("Avg. Speed")
			.el(MapPlot.class, (viz, data) -> {

				viz.title = "Simulated Average Speed per Link by bike";
				viz.center = data.context().getCenter();
				viz.zoom = data.context().mapZoomLevel;
				viz.height = 7.5;
				viz.width = 2.0;
				viz.setShape("/analysis/network/network.avro", "id");
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

	}
}
