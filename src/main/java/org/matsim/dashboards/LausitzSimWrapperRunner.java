/* *********************************************************************** *
 * project: org.matsim.*
 * Controler.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2007 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.dashboards;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.application.ApplicationUtils;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.run.prepare.PrepareNetwork;
import org.matsim.run.scenarios.LausitzScenario;
import org.matsim.simwrapper.Dashboard;
import org.matsim.simwrapper.SimWrapper;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.simwrapper.dashboard.EmissionsDashboard;
import org.matsim.simwrapper.dashboard.NoiseDashboard;
import org.matsim.simwrapper.dashboard.TripDashboard;
import org.matsim.vehicles.MatsimVehicleWriter;
import picocli.CommandLine;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.List;

@CommandLine.Command(
	name = "simwrapper",
	description = "Run additional analysis and create SimWrapper dashboard for existing run output."
)
public final class LausitzSimWrapperRunner implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(LausitzSimWrapperRunner.class);

	@CommandLine.Parameters(arity = "1..*", description = "Path to run output directories for which dashboards are to be generated.")
	private List<Path> inputPaths;

	@CommandLine.Mixin
	private final ShpOptions shp = new ShpOptions();

	@CommandLine.Option(names = "--noise", defaultValue = "false", description = "create noise dashboard")
	private boolean noise;
	@CommandLine.Option(names = "--trips", defaultValue = "false", description = "create trips dashboard")
	private boolean trips;
	@CommandLine.Option(names = "--emissions", defaultValue = "false", description = "create emission dashboard")
	private boolean emissions;


	public LausitzSimWrapperRunner(){
//		public constructor needed for testing purposes.
	}

	@Override
	public Integer call() throws Exception {

		if (!noise && !trips && !emissions){
			throw new IllegalArgumentException("you have not configured any dashboard to be created! Please use command line parameters!");
		}

		for (Path runDirectory : inputPaths) {
			log.info("Running on {}", runDirectory);

			String configPath = ApplicationUtils.matchInput("config.xml", runDirectory).toString();
			Config config = ConfigUtils.loadConfig(configPath);
			SimWrapper sw = SimWrapper.create(config);

			SimWrapperConfigGroup simwrapperCfg = ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class);
			if (shp.isDefined()){
				simwrapperCfg.defaultParams().shp = shp.getShapeFile();
			}
			//skip default dashboards
			simwrapperCfg.defaultDashboards = SimWrapperConfigGroup.Mode.disabled;

			//add dashboards according to command line parameters
//			if more dashboards are to be added here, we need to check if noise==true before adding noise dashboard here
			if (noise) {
				sw.addDashboard(Dashboard.customize(new NoiseDashboard(config.global().getCoordinateSystem())).context("noise"));
			}

			if (trips) {
				sw.addDashboard(new TripDashboard(
					"lausitz_mode_share.csv",
					"lausitz_mode_share_per_dist.csv",
					"lausitz_mode_users.csv")
					.withGroupedRefData("lausitz_mode_share_per_group_dist_ref.csv", "age", "economic_status", "income")
					.withDistanceDistribution("lausitz_mode_share_distance_distribution.csv"));
			}

			if (emissions) {
				sw.addDashboard(Dashboard.customize(new EmissionsDashboard(config.global().getCoordinateSystem())).context("emissions"));

				LausitzScenario.setEmissionsConfigs(config);
				ConfigUtils.writeConfig(config, configPath);

				Config dummyConfig = new Config();

				String networkPath = ApplicationUtils.matchInput("output_network.xml.gz", runDirectory).toString();
				String vehiclesPath = ApplicationUtils.matchInput("output_vehicles.xml.gz", runDirectory).toString();
				String transitVehiclesPath = ApplicationUtils.matchInput("output_transitVehicles.xml.gz", runDirectory).toString();

				dummyConfig.network().setInputFile(networkPath);
				dummyConfig.vehicles().setVehiclesFile(vehiclesPath);
				dummyConfig.transit().setVehiclesFile(transitVehiclesPath);

				Scenario scenario = ScenarioUtils.loadScenario(dummyConfig);

//				adapt network and veh types for emissions analysis like in LausitzScenario base run class
				PrepareNetwork.prepareEmissionsAttributes(scenario.getNetwork());
				LausitzScenario.prepareVehicleTypesForEmissionAnalysis(scenario);

//				overwrite outputs with adapted files
				NetworkUtils.writeNetwork(scenario.getNetwork(), networkPath);
				new MatsimVehicleWriter(scenario.getVehicles()).writeFile(vehiclesPath);
				new MatsimVehicleWriter(scenario.getTransitVehicles()).writeFile(transitVehiclesPath);
			}


			try {
				sw.generate(runDirectory, true);
				sw.run(runDirectory);
			} catch (IOException e) {
				throw new InterruptedIOException();
			}
		}

		return 0;
	}

	public static void main(String[] args) {
		new LausitzSimWrapperRunner().execute(args);

	}

}
