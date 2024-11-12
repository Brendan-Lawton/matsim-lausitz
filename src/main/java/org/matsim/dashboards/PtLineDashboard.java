package org.matsim.dashboards;

import org.matsim.run.analysis.PtLineAnalysis;
import org.matsim.run.scenarios.LausitzScenario;
import org.matsim.simwrapper.Dashboard;
import org.matsim.simwrapper.Header;
import org.matsim.simwrapper.Layout;
import org.matsim.simwrapper.viz.*;
import tech.tablesaw.plotly.traces.BarTrace;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows information about an optional policy case, which implements a pt line between Cottbus and Hoyerswerda.
 * It also compares the agents and their trips using the new pt line with their respective trips in the base case.
 */
public class PtLineDashboard implements Dashboard {
	private final String basePath;
	private static final String SHARE = "share";

	PtLineDashboard(String basePath) {
		if (!basePath.endsWith("/")) {
			basePath += "/";
		}
		this.basePath = basePath;
	}

	@Override
	public void configure(Header header, Layout layout) {
		header.title = "Pt Line Dashboard";
		header.description = "Shows statistics about agents, who used the newly implemented pt line between Cottbus and Hoyerswerda " +
			"and compares to the trips of those agents in the base case.";

		String[] args = new ArrayList<>(List.of("--base-path", basePath)).toArray(new String[0]);

		layout.row("first")
			.el(Tile.class, (viz, data) -> {
			viz.dataset = data.compute(PtLineAnalysis.class, "mean_travel_stats.csv", args);
			viz.height = 0.1;
		});

		layout.row("second")
			.el(Bar.class, (viz, data) -> {
				viz.title = "Agents per income group";
				viz.stacked = false;
				viz.dataset = data.compute(PtLineAnalysis.class, "pt_persons_income_groups.csv", args);
				viz.x = "incomeGroup";
				viz.xAxisName = "income group";
				viz.yAxisName = SHARE;
				viz.columns = List.of(SHARE);
			})
			.el(Bar.class, (viz, data) -> {
				viz.title = "Mean score per income group";
				viz.stacked = false;
				viz.dataset = data.compute(PtLineAnalysis.class, "pt_persons_mean_score_per_income_group.csv", args);
				viz.x = "incomeGroup";
				viz.xAxisName = "income group";
				viz.yAxisName = "mean score";
				viz.columns = List.of("mean_score_base", "mean_score_policy");
			})
			.el(Bar.class, (viz, data) -> {
				viz.title = "Agents per age group";
				viz.stacked = false;
				viz.dataset = data.compute(PtLineAnalysis.class, "pt_persons_age_groups.csv", args);
				viz.x = "ageGroup";
				viz.xAxisName = "age group";
				viz.yAxisName = SHARE;
				viz.columns = List.of(SHARE);
		});

		layout.row("third")
			.el(Plotly.class, (viz, data) -> {
				viz.title = "Modal split (base case)";
				viz.description = "Shows mode of agents in base case, which used the new pt line in the policy case.";

				viz.layout = tech.tablesaw.plotly.components.Layout.builder()
					.barMode(tech.tablesaw.plotly.components.Layout.BarMode.STACK)
					.build();

				Plotly.DataSet ds = viz.addDataset(data.compute(PtLineAnalysis.class, "pt_persons_base_modal_share.csv", args))
					.constant("source", "Base Case Mode")
					.aggregate(List.of("main_mode"), SHARE, Plotly.AggrFunc.SUM);

				viz.mergeDatasets = true;
				viz.addTrace(BarTrace.builder(Plotly.OBJ_INPUT, Plotly.INPUT).orientation(BarTrace.Orientation.HORIZONTAL).build(),
					ds.mapping()
						.name("main_mode")
						.y("source")
						.x(SHARE)
				);
			})
			.el(Hexagons.class, (viz, data) -> {

				viz.title = "Pt line agents home locations";
				viz.center = data.context().getCenter();
				viz.zoom = data.context().mapZoomLevel;
				viz.height = 7.5;
				viz.width = 2.0;

				viz.file = data.compute(PtLineAnalysis.class, "pt_persons_home_locations.csv");
				viz.projection = LausitzScenario.CRS;
				viz.addAggregation("home locations", "person", "home_x", "home_y");
			});

			layout.row("fourth")
				.el(Table.class, (viz, data) -> {
					viz.title = "Executed scores";
					viz.description = "... in base and policy case";
					viz.dataset = data.compute(PtLineAnalysis.class, "pt_persons_executed_score.csv");
					viz.showAllRows = true;
				})
				.el(Table.class, (viz, data) -> {
					viz.title = "Travel times";
					viz.description = "... in base and policy case";
					viz.dataset = data.compute(PtLineAnalysis.class, "pt_persons_trav_time.csv");
					viz.showAllRows = true;
				})
				.el(Table.class, (viz, data) -> {
					viz.title = "Travel distances";
					viz.description = "... in base and policy case";
					viz.dataset = data.compute(PtLineAnalysis.class, "pt_persons_traveled_distance.csv");
					viz.showAllRows = true;
				});
	}
}
