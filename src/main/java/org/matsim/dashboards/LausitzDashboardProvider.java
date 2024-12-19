package org.matsim.dashboards;

import org.matsim.core.config.Config;
import org.matsim.simwrapper.Dashboard;
import org.matsim.simwrapper.DashboardProvider;
import org.matsim.simwrapper.SimWrapper;
import org.matsim.simwrapper.dashboard.EmissionsDashboard;
import org.matsim.simwrapper.dashboard.TripDashboard;

import java.util.List;

/**
 * Dashboards for the Lausitz scenario.
 */
public class LausitzDashboardProvider implements DashboardProvider {
	@Override
	public List<Dashboard> getDashboards(Config config, SimWrapper simWrapper) {

		TripDashboard trips = new TripDashboard(
			"lausitz_mode_share.csv",
			"lausitz_mode_share_per_dist.csv",
			"lausitz_mode_users.csv")
			.withGroupedRefData("lausitz_mode_share_per_group_dist_ref.csv", "age", "economic_status", "income")
			.withDistanceDistribution("lausitz_mode_share_distance_distribution.csv")
			.setAnalysisArgs("--person-filter", "subpopulation=person");

		return List.of(trips
//			new EmissionsDashboard(config.global().getCoordinateSystem()),
//			new PtLineDashboard("https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/lausitz/output/v2024.2/")
//			new CycleAnalysisDashboard(config.controller().getOutputDirectory())
//			the NoiseAnalysis is not run here because it needs more RAM than the entire simulation,
//			which leads to VM crashes and prevents other analysis to run. We have to run it separately (e.g. with LausitzSimWrapperRunner)
		);
	}
}
