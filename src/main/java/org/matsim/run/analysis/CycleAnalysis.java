package org.matsim.run.analysis;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.CommandSpec;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CsvOptions;
import org.matsim.application.options.InputOptions;
import org.matsim.application.options.OutputOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.groups.NetworkConfigGroup;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.network.filter.NetworkFilterManager;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.vehicles.Vehicle;
import picocli.CommandLine;
import tech.tablesaw.api.*;
import tech.tablesaw.columns.Column;
import tech.tablesaw.io.csv.CsvReadOptions;
import tech.tablesaw.joining.DataFrameJoiner;
import tech.tablesaw.selection.Selection;

import java.io.FileWriter;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static java.lang.Math.abs;
import static org.matsim.application.ApplicationUtils.globFile;
import static tech.tablesaw.aggregate.AggregateFunctions.*;

@CommandLine.Command(name = "cycle-highway", description = "Calculates various cycle highway related metrics.")
@CommandSpec(
	requireRunDirectory=true,
	produces = {"mode_share.csv", "mode_share_base.csv", "mode_shift.csv", "mean_travel_stats.csv", "bike_income_groups.csv", "bike_income_groups_base.csv", "allModes_income_groups_base_leipzig.csv",
		"bike_income_groups_base_leipzig.csv", "car_income_groups_base_leipzig.csv", "walk_income_groups_base_leipzig.csv", "ride_income_groups_base_leipzig.csv", "traffic_stats_by_road_type_and_hour.csv",
		"pt_income_groups_base_leipzig.csv", "cycle_highway_agents_trip_start_end.csv", "cycle_highways.shp", "cyclist_demo_stats.csv", "traffic_stats_by_link_daily_bike.csv"}
)

public class CycleAnalysis implements MATSimAppCommand {
	private static final Logger log = LogManager.getLogger(CycleAnalysis.class);

	@CommandLine.Mixin
	private InputOptions input = InputOptions.ofCommand(CycleAnalysis.class);
	@CommandLine.Mixin
	private OutputOptions output = OutputOptions.ofCommand(CycleAnalysis.class);
//	@CommandLine.Option(names = "--base-path", description = "Path to run directory of base case.", required = true)
//	private Path basePath;
	@CommandLine.Mixin
	private ShpOptions shp;
	@CommandLine.Option(names = "--highways-shp-path", description = "Path to run directory of base case.", required = false)
	private String highwaysShpPath;
	@CommandLine.Option(names = "--crs", description = "CRS for shp files.", defaultValue = "EPSG:25832")
	private String crs;
	@CommandLine.Option(names = "--dist-groups", split = ",", description = "List of distances for binning", defaultValue = "0.,1000.,2000.,5000.,10000.,20000.")
	private List<Double> distGroups;
	@CommandLine.Option(names = "--income-groups", split = ",", description = "List of income for binning. Derived from SrV 2018.", defaultValue = "0.,500.,900.,1500.,2000.,2600.,3000.,3600.,4600.,5600.")
	private List<Double> incomeGroups;

	List<String> modeOrder = null;
	//	cannot use the original String from class CreateBicycleHighwayNetwork because the class is on another branch. In the matsim version of this branch Simwrapper was not yet implemented
	private static final String LINK_PREFIX = "cycle-highway-";
	private static final String INCOME_SUFFIX = "_income_groups_base_leipzig.csv";
	private static final String PERSON = "person";
	private static final String INCOME = "income";
	private static final String TRAV_TIME = "trav_time";
	private static final String TRAV_DIST = "traveled_distance";
	private static final String MAIN_MODE = "main_mode";
	private static final String LONG_MODE = "longest_distance_mode";
	private static final String TRIP_ID = "trip_id";
	//	private static final String COUNT_PERSON = "Count [person]";
	private final Map<Id<Vehicle>, String> bikers = new HashMap<>();
	private final Map<String, List<Integer>> highwayPersons = new HashMap<>();
	private String baseURL = "";

	public static void main(String[] args) {
		new CycleAnalysis().execute();
	}

	@Override
	public Integer call() throws Exception {

		//		all necessary file input paths are defined here
		String eventsPath = globFile(input.getRunDirectory(), "*output_events.xml.gz").toString();
		String personsPath = globFile(input.getRunDirectory(), "*output_persons.csv.gz").toString();
		String tripsPath = globFile(input.getRunDirectory(), "*output_trips.csv.gz").toString();
		String networkPath = globFile(input.getRunDirectory(), "*output_network.xml.gz").toString();
		String trafficCsvPath = input.getRunDirectory() + "/analysis/traffic/traffic_stats_by_link_daily.csv";
		Set<String> modes = Collections.singleton("bike");

		EventsManager manager = EventsUtils.createEventsManager();
		manager.addHandler(new CycleHighwayEventHandler());
		manager.initProcessing();

		MatsimEventsReader reader = new MatsimEventsReader(manager);
		reader.readFile(eventsPath);
		manager.finishProcessing();

//		read necessary tables
		Table persons = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(personsPath))
			.columnTypesPartial(Map.of(PERSON, ColumnType.TEXT, INCOME, ColumnType.DOUBLE, "subpopulation", ColumnType.TEXT))
			.sample(false)
			.separator(CsvOptions.detectDelimiter(personsPath)).build());

		Map<String, ColumnType> columnTypes = new HashMap<>(Map.of(PERSON, ColumnType.TEXT,
			TRAV_TIME, ColumnType.STRING, "dep_time", ColumnType.STRING,
			LONG_MODE, ColumnType.STRING, MAIN_MODE, ColumnType.STRING, TRAV_DIST, ColumnType.DOUBLE,
			"first_act_x", ColumnType.DOUBLE, "first_act_y", ColumnType.DOUBLE, TRIP_ID, ColumnType.TEXT));

		Table trips = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(tripsPath))
			.columnTypesPartial(columnTypes)
			.sample(false)
			.separator(CsvOptions.detectDelimiter(tripsPath)).build());

		Map<String, ColumnType> trafficColumnTypes = new HashMap<>(Map.ofEntries(Map.entry("link_id", ColumnType.TEXT), Map.entry("lane_km", ColumnType.DOUBLE), Map.entry("road_capacity_utilization", ColumnType.DOUBLE),
			Map.entry("avg_speed", ColumnType.DOUBLE), Map.entry("congestion_index", ColumnType.DOUBLE), Map.entry("speed_performance_index", ColumnType.DOUBLE),
			Map.entry("simulated_traffic_volume", ColumnType.DOUBLE), Map.entry("vol_truck40t", ColumnType.DOUBLE), Map.entry("vol_truck8t", ColumnType.DOUBLE),
			Map.entry("vol_longDistanceFreight", ColumnType.DOUBLE), Map.entry("vol_truck18t", ColumnType.DOUBLE), Map.entry("vol_bike", ColumnType.DOUBLE), Map.entry("vol_car", ColumnType.DOUBLE)));

		Table traffic = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(trafficCsvPath))
			.columnTypesPartial(trafficColumnTypes)
			.sample(false)
			.separator(CsvOptions.detectDelimiter(trafficCsvPath)).build());

		Table bikeTraffic = filterBikeTrafficCsv(traffic);



//		only analyze person agents
//		basePersons = filterPersonAgents(basePersons);
		persons = filterPersonAgents(persons);


//		create labels for dist and income groups
		List<String> distLabels = getLabels(distGroups);
		List<String> incomeLabels = getLabels(incomeGroups);

//		add group columns for dist and income
//		addGroupColumn(basePersons, INCOME, incomeGroups, incomeLabels);
		addGroupColumn(persons, INCOME, incomeGroups, incomeLabels);

//		filter Leipzig agents
//		Table basePersonsLeipzig = filterLeipzigAgents(basePersons);
//		Table personsLeipzig = filterLeipzigAgents(persons);

//		the 2 populations should consist of the same persons
//		if (basePersonsLeipzig.rowCount() != personsLeipzig.rowCount()) {
//			log.fatal("Number of agents living in Leipzig for base ({}) and policy case ({}) are not the same!", basePersonsLeipzig.rowCount(), personsLeipzig.rowCount());
//			throw new IllegalStateException();
//		}

		// Use longest_distance_mode where main_mode is not present
		trips.stringColumn(MAIN_MODE)
			.set(trips.stringColumn(MAIN_MODE).isMissing(),
				trips.stringColumn(LONG_MODE));
//		baseTrips.stringColumn(MAIN_MODE)
//			.set(baseTrips.stringColumn(MAIN_MODE).isMissing(),
//				baseTrips.stringColumn(LONG_MODE));


//		calc modal split for base and policy
		writeModeShare(trips, persons, distLabels,  "mode_share.csv");
//		writeModeShare(baseTrips, basePersons, distLabels, "mode_share_base.csv");

//		calc modal shift base to policy
//		writeModeShift(trips, baseTrips);

//		join persons and trips
		Table joined = new DataFrameJoiner(trips, PERSON).inner(persons);
//		Table baseJoined = new DataFrameJoiner(baseTrips, PERSON).inner(basePersons);

//		write income group distr for mode bike in policy and base
		writeIncomeGroups(joined, incomeLabels, TransportMode.bike,  "_income_groups.csv");

//		filter for bike trips
		Table bikeJoined = filterModeAgents(joined, TransportMode.bike);

//		filter for trips "cycleHighwayAgents" map
		IntList idx = new IntArrayList();

		for (int i = 0; i < bikeJoined.rowCount(); i++) {
			Row row = bikeJoined.row(i);

			int tripStart = durationToSeconds(row.getString("dep_time"));
//			waiting time already included in travel time
			int travelTime = durationToSeconds(row.getString(TRAV_TIME));
			row.setString(TRAV_TIME, Integer.toString(travelTime));
			System.out.println(row.getString(TRAV_TIME));
//			List<Integer> enterTimes = highwayPersons.get(row.getString(PERSON));

//			for (int enterTime : enterTimes) {
//				if (Range.of(tripStart, tripStart + travelTime).contains(enterTime)) {
//					idx.add(i);
//				}
//			}
		}
//		write trip start and end of every trip using cycle highway to csv
//		bikeJoined = bikeJoined.where(Selection.with(idx.toIntArray())).selectColumns(PERSON, "start_x", "start_y", "end_x", "end_y");
//		bikeJoined.write().csv(output.getPath("cycle_highway_agents_trip_start_end.csv").toFile());

//		here: filter base trip ids for trip ids of bikeJoined
//		TextColumn tripIdCol = baseJoined.textColumn(TRIP_ID);
//		baseJoined = baseJoined.where(tripIdCol.isIn(bikeJoined.textColumn(TRIP_ID)));

		Column<?> uniquePersons = bikeJoined.column("person").unique();

		// Create a selection for the first occurrence of each unique value
		Selection selection = Selection.with();
		for (Object uniquePerson : uniquePersons) {
			int firstIndex = bikeJoined.column("person").asList().indexOf(uniquePerson.toString());
			selection = selection.or(Selection.with(firstIndex));
		}

		// Filter the table to include only rows with unique "Persons"
		Table uniquePersonsBikeJoined = bikeJoined.where(selection);

		calcAndWriteMeanStats(bikeJoined);
		calcAndWriteDemoStats(uniquePersonsBikeJoined);

//		writeHighwaysShpFile();
		bikeTraffic.write().csv(input.getRunDirectory() + "/analysis/analysis/traffic_stats_by_link_daily_bike.csv");


		return 0;
	}

	private void calcAndWriteDemoStats(Table uniqueAgents) throws IOException {
		IntColumn ageCol = uniqueAgents.intColumn("age");
		double meanAge = ageCol.mean();
		double medianAge = ageCol.median();
		double maleCyclists = 0;
		double femaleCyclists = 0;
		double numOfAgents = uniqueAgents.stringColumn("sex").size();

		for (String gender: uniqueAgents.stringColumn("sex")) {
			if (gender.equals("m")) {
				maleCyclists++;
			} else if (gender.equals("f")) {
				femaleCyclists++;
			}
		}

		double percentageFemaleCyclists = (femaleCyclists / numOfAgents) * 100;
		double percentageMaleCyclists = (maleCyclists / numOfAgents) * 100;

		DecimalFormat f = new DecimalFormat("0.00", new DecimalFormatSymbols(Locale.ENGLISH));
		try (CSVPrinter printer = new CSVPrinter(new FileWriter(output.getPath("cyclist_demo_stats.csv").toString()),
			CSVFormat.DEFAULT.builder()
				.setQuote(null)
				.setDelimiter(',')
				.setRecordSeparator("\r\n")
				.build())) {
			printer.printRecord("\"mean age of cyclists\"", f.format(Math.round(meanAge)));
//			printer.printRecord("\"median age of cyclists\"", f.format(medianAge));
			printer.printRecord("\"percentage of male bike riders\"", f.format(Math.round(percentageMaleCyclists)));
			printer.printRecord("\"percentage of female bike riders\"", f.format(Math.round(percentageFemaleCyclists)));
		}
	}

	private void calcAndWriteMeanStats(Table bikeJoined) throws IOException {
		DoubleColumn distanceCol = bikeJoined.doubleColumn(TRAV_DIST);
		StringColumn stringCol = bikeJoined.stringColumn("TRAV_TIME");

		double[] doubleValues = new double[stringCol.size()];

		for (int i = 0; i < stringCol.size(); i++) {
			String value = stringCol.get(i);
			try {
				doubleValues[i] = Double.parseDouble(value);
			} catch (Exception e) {
				doubleValues[i] = Double.NaN;
			}
		}

		DoubleColumn doubleTimeCol = DoubleColumn.create("TRAV_TIME_Seconds", doubleValues);


//		calc mean / median distances / times
		double meanDist = distanceCol.mean();
		double medianDist = distanceCol.median();
		double meanTravTime = doubleTimeCol.mean();
		double medianTravTime = doubleTimeCol.median();

		DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");
		long meanTravTimeSec = (long) meanTravTime;
		long medianTravTimeSec = (long) medianTravTime;

		long hoursMean = meanTravTimeSec / 3600;
		long minutesMean = abs((hoursMean*60) - meanTravTimeSec / 60);
		long secondsMean= (long) (((meanTravTimeSec / 60.0) - minutesMean) * 60.0);

		long hoursMedian = medianTravTimeSec / 3600;
		long minutesMedian = abs(hoursMedian - medianTravTimeSec / 60);
		long secondsMedian = (long) (((medianTravTimeSec / 60.0) - minutesMedian) * 60.0);;

		String meanTravTimeFormatted = String.format("%02d:%02d:%02d", hoursMean, minutesMean, secondsMean);
		String medianTravTimeFormatted = String.format("%02d:%02d:%02d", hoursMedian, minutesMedian, secondsMedian);

		//		write mean stats to csv
		DecimalFormat f = new DecimalFormat("0.00", new DecimalFormatSymbols(Locale.ENGLISH));
//		System.out.println(basePath);
		try (CSVPrinter printer = new CSVPrinter(new FileWriter(output.getPath("mean_travel_stats.csv").toString()),
			CSVFormat.DEFAULT.builder()
				.setQuote(null)
				.setDelimiter(',')
				.setRecordSeparator("\r\n")
				.build())) {
			printer.printRecord("\"mean travel distance (in meters)\"", f.format(Math.round(meanDist)));
//			printer.printRecord("\"median travel distance\"", f.format(medianDist));
			printer.printRecord("\"mean travel time\"", meanTravTimeFormatted);
//			printer.printRecord("\"median travel time\"", medianTravTimeFormatted);
		}
	}


	private static Table normalizeColumns(Table table) {
		for (Column<?> c : table.columns()) {
			int start = c.name().indexOf("[");
			int end = c.name().indexOf("]");

			if (start > -1 && end > -1) {
				c.setName(c.name().substring(start + 1, end));
			}
		}

		return table;
	}

	private Table weightedMeanBy(Table table, List<String> aggr, String... by) {
		Table first = multiplyWithLinkLength(table).summarize(aggr, sum).by(by);
		return divideByLength(normalizeColumns(first));
	}

	private Table multiplyWithLinkLength(Table table) {

		Table copy = Table.create();

		for (Column<?> column : table.columns()) {

			if (column instanceof DoubleColumn d && !column.name().equals("lane_km")) {
				DoubleColumn multiplied = d.multiply(table.doubleColumn("lane_km")).setName(column.name());
				copy.addColumns(multiplied);
			} else
				copy.addColumns(column);
		}
		return copy;
	}

	private Table divideByLength(Table table) {

		Table copy = Table.create();
		for (Column<?> column : table.columns()) {

			if (column instanceof DoubleColumn d && !column.name().equals("lane_km")) {
				String name = column.name();
				DoubleColumn divided = d.divide(table.doubleColumn("lane_km")).setName(name);
				copy.addColumns(divided);
			} else
				copy.addColumns(column);
		}

		return copy;
	}

	private void roundColumns(Table table) {

		for (Column<?> column : table.columns()) {
			if (column instanceof DoubleColumn d) {
				d.set(Selection.withRange(0, d.size()), d.multiply(1000).round().divide(1000));
			}
		}
	}

	private void writeIncomeGroups(Table joined, List<String> incomeLabels, String mode, String outputFile) {

//		only filter if specific mode is given
		if (!mode.equals("allModes")){
			joined = filterModeAgents(joined, mode);
		}

		Table aggr = joined.summarize(TRIP_ID, count).by("income_group");

		DoubleColumn countColumn = aggr.doubleColumn("Count [trip_id]");
		DoubleColumn share = countColumn.divide(countColumn.sum()).setName("share");
		aggr.addColumns(share);

		// Sort by income_group
		Comparator<Row> cmp = Comparator.comparingInt(row -> incomeLabels.indexOf(row.getString("income_group")));
		aggr = aggr.sortOn(cmp);

		aggr.write().csv(output.getPath(mode + outputFile).toFile());
	}

	private void writeModeShift(Table trips, Table baseTrips) {
		baseTrips.column(MAIN_MODE).setName("original_mode");

		Table joined = new DataFrameJoiner(trips, TRIP_ID).inner(true, baseTrips);
		Table aggr = joined.summarize(TRIP_ID, count).by("original_mode", MAIN_MODE);

		aggr.write().csv(output.getPath("mode_shift.csv").toFile());

//		rename column again because we need the column as main_mode later
		baseTrips.column("original_mode").setName(MAIN_MODE);
	}

	private void writeModeShare(Table trips, Table persons, List<String> labels, String outputFile) {

//		join needed to filter for Leipzig agents only
		Table joined = new DataFrameJoiner(trips, PERSON).inner(persons);

		addGroupColumn(joined, TRAV_DIST, distGroups, labels);

		Table aggr = joined.summarize(TRIP_ID, count).by(TRAV_DIST + "_group", MAIN_MODE);

		DoubleColumn share = aggr.numberColumn(2).divide(aggr.numberColumn(2).sum()).setName("share");
		aggr.replaceColumn(2, share);

		// Sort by dist_group and mode
		Comparator<Row> cmp = Comparator.comparingInt(row -> labels.indexOf(row.getString(TRAV_DIST + "_group")));
		aggr = aggr.sortOn(cmp.thenComparing(row -> row.getString(MAIN_MODE)));

		aggr.write().csv(output.getPath(outputFile).toFile());

		// Derive mode order if not given
		if (modeOrder == null) {
			modeOrder = new ArrayList<>();
			for (Row row : aggr) {
				String mainMode = row.getString(MAIN_MODE);
				if (!modeOrder.contains(mainMode)) {
					modeOrder.add(mainMode);
				}
			}
		}
	}

	private Table filterBikeTrafficCsv(Table traffic) {
		Table bikeTraffic = traffic.where(traffic.numberColumn("vol_bike").isGreaterThan(0.0));
		return bikeTraffic;
	}

	private List<String> getLabels(List<Double> groups) {
		List<String> labels = new ArrayList<>();
		for (int i = 0; i < groups.size() - 1; i++) {
			labels.add(String.format("%d - %d", groups.get(i).intValue(), groups.get(i + 1).intValue()));
		}
		labels.add(groups.get(groups.size() - 1) + "+");
		groups.add(Double.MAX_VALUE);
		return labels;
	}

	private void addGroupColumn(Table table, String valueLabel, List<Double> groups, List<String> labels) {
		StringColumn group = table.doubleColumn(valueLabel)
			.map(dist -> cut(dist, groups, labels), ColumnType.STRING::create).setName(valueLabel + "_group");
		table.addColumns(group);
	}

	private static String cut(double value, List<Double> groups, List<String> labels) {
		int idx = Collections.binarySearch(groups, value);

		if (idx >= 0)
			return labels.get(idx);

		int ins = -(idx + 1);
		return labels.get(ins - 1);
	}

	private Table filterLeipzigAgents(Table persons) {
		Geometry geometry = shp.getGeometry();
		GeometryFactory f = new GeometryFactory();

		IntList idx = new IntArrayList();
		for (int i = 0; i < persons.rowCount(); i++) {
			Row row = persons.row(i);
			Point p = f.createPoint(new Coordinate(row.getDouble("first_act_x"), row.getDouble("first_act_y")));
			if (geometry.contains(p)) {
				idx.add(i);
			}
		}
		return persons.where(Selection.with(idx.toIntArray()));
	}

	private Table filterPersonAgents(Table persons) {
		IntList idx = new IntArrayList();
		for (int i = 0; i < persons.rowCount(); i++) {
			Row row = persons.row(i);
			String subPop = row.getText("subpopulation");

			if (subPop.equals(PERSON)) {
				idx.add(i);
			}
		}
		return persons.where(Selection.with(idx.toIntArray()));
	}

	private Network filterNetwork(Network network, Set<String> modes) {

		Network unfiltered = network;
		NetworkFilterManager manager = new NetworkFilterManager(unfiltered, new NetworkConfigGroup());

		// Must contain one of the analyzed modes
		manager.addLinkFilter(l -> l.getAllowedModes().stream().anyMatch(s -> modes.contains(s)));

//		if (shp.isDefined()) {
//			String crs = ProjectionUtils.getCRS(unfiltered);
//			ShpOptions.Index index = shp.createIndex(crs != null ? crs : shp.getShapeCrs(), "_");
//			manager.addLinkFilter(l -> index.contains(l.getCoord()));
//		}

		return manager.applyFilters();
	}

	private Table filterModeAgents(Table trips, String mode) {
		IntList idx = new IntArrayList();
		for (int i = 0; i < trips.rowCount(); i++) {
			Row row = trips.row(i);
			String mainMode = row.getString(MAIN_MODE);

			if (mainMode.equals(mode)) {
				idx.add(i);
			}
		}
		return trips.where(Selection.with(idx.toIntArray()));
	}

	private int durationToSeconds(String d) {
		String[] split = d.split(":");
		return (Integer.parseInt(split[0]) * 60 * 60) + (Integer.parseInt(split[1]) * 60) + Integer.parseInt(split[2]);
	}

	private final class CycleHighwayEventHandler implements PersonEntersVehicleEventHandler, LinkEnterEventHandler {

		@Override
		public void handleEvent(PersonEntersVehicleEvent event) {
//			register personId with vehId to get personId in LinkEnterEvent
			if (event.getVehicleId().toString().contains(TransportMode.bike)) {
				bikers.putIfAbsent(event.getVehicleId(), event.getPersonId().toString());
			}
		}

		@Override
		public void handleEvent(LinkEnterEvent event) {
			if (event.getLinkId().toString().contains(LINK_PREFIX)) {
				if (!highwayPersons.containsKey(bikers.get(event.getVehicleId()))) {
					highwayPersons.put(bikers.get(event.getVehicleId()), new ArrayList<>());
				}
				highwayPersons.get(bikers.get(event.getVehicleId())).add((int) event.getTime());
			}
		}
	}
}
