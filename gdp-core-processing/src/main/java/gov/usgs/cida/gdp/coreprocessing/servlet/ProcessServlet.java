package gov.usgs.cida.gdp.coreprocessing.servlet;

import gov.usgs.cida.gdp.coreprocessing.analysis.grid.FeatureCategoricalGridCoverage;
import gov.usgs.cida.gdp.coreprocessing.bean.FileLocationBean;
import gov.usgs.cida.gdp.utilities.FileHelper;
import gov.usgs.cida.gdp.utilities.XmlUtils;
import gov.usgs.cida.gdp.utilities.bean.AckBean;
import gov.usgs.cida.gdp.utilities.bean.AvailableFilesBean;
import gov.usgs.cida.gdp.utilities.bean.ErrorBean;
import gov.usgs.cida.gdp.utilities.bean.ShapeFileSetBean;
import gov.usgs.cida.gdp.utilities.bean.XmlReplyBean;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Formatter;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import javax.mail.MessagingException;
import javax.mail.internet.AddressException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.geotools.filter.text.cql2.CQLException;

import org.slf4j.LoggerFactory;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.SchemaException;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.operation.TransformException;

import ucar.ma2.InvalidRangeException;
import ucar.ma2.Range;
import ucar.nc2.VariableSimpleIF;
import ucar.nc2.constants.FeatureType;
import ucar.nc2.dataset.CoordinateAxis1DTime;
import ucar.nc2.dt.GridDatatype;
import ucar.nc2.dt.grid.GridDataset;
import ucar.nc2.ft.FeatureDataset;
import ucar.nc2.ft.FeatureDatasetFactoryManager;
import ucar.nc2.ft.FeatureDatasetPoint;
import ucar.nc2.ft.StationTimeSeriesFeatureCollection;
import ucar.nc2.units.DateRange;

import gov.usgs.cida.gdp.communication.EmailHandler;
import gov.usgs.cida.gdp.communication.bean.EmailMessageBean;
import gov.usgs.cida.gdp.coreprocessing.ShapefileToFeatureCollection;
import gov.usgs.cida.gdp.coreprocessing.analysis.StationDataCSVWriter;
import gov.usgs.cida.gdp.coreprocessing.analysis.grid.FeatureCoverageWeightedGridStatistics;
import gov.usgs.cida.gdp.coreprocessing.analysis.grid.FeatureCoverageWeightedGridStatisticsWriter.Statistic;
import gov.usgs.cida.gdp.coreprocessing.servlet.ProcessServlet.GroupBy.StationOption;
import gov.usgs.cida.gdp.mapping.NHDService;
import gov.usgs.cida.gdp.utilities.HTTPUtils;
import ucar.nc2.NetcdfFile;

/**
 * Servlet implementation class ProcessServlet
 */
public class ProcessServlet extends HttpServlet {

	static {
		try {
			NetcdfFile
					.registerIOProvider(ucar.nc2.iosp.geotiff.GeoTiffIOServiceProvider.class);
		} catch (Exception e) {
		}
	}

	// // START - MOVEME
	// IVAN, move this out where ever you see fit... values in this enum should
	// be reported-to/used-by front end in some manner... right now, too much
	// loose
	// coupling.
	public enum DelimiterOption {

		c("[comma]", ","), t("[tab]", "\t"), s("[space]", " ");
		public final String description;
		public final String delimiter;

		private DelimiterOption(String description, String value) {
			this.description = description;
			this.delimiter = value;
		}

		public static DelimiterOption getDefault() {
			return c;
		}

		@Override
		public String toString() {
			return description;
		}
	}

	public static class GroupBy {

		public enum StationOption {

			station("Station"), variable("Variable");
			public final String description;

			private StationOption(String description) {
				this.description = description;
			}

			@Override
			public String toString() {
				return description;
			}

			public static StationOption getDefault() {
				return station;
			}
		}

		public enum GridOption {

			attributes("Attributes"), statistics("Statistics");
			public final String description;

			private GridOption(String description) {
				this.description = description;
			}

			public static GridOption getDefault() {
				return attributes;
			}
		}
	}

	// // END - MOVEME

	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse
	 *      response)
	 */
	@Override
	protected void doGet(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {
		Long start = Long.valueOf(new Date().getTime());

		String command = request.getParameter("command");

		if ("submitforprocessing".equals(command)) {
			// Geometry
			String shapeSet = request.getParameter("shapeset");
			String attribute = request.getParameter("attribute");
			String[] features = request.getParameterValues("feature");
			String[] outputStats = request.getParameterValues("outputstat");

			String lat = request.getParameter("lat");
			String lon = request.getParameter("lon");

			// THREDDS
			String dataset = request.getParameter("dataset");
			String[] dataTypes = request.getParameterValues("datatype");
			String from = request.getParameter("from");
			String to = request.getParameter("to");

			// WCS
			String wcsServer = request.getParameter("wcsserver");
			String wcsCoverage = request.getParameter("wcscoverage");
			String wcsGridCRS = request.getParameter("wcsgridcrs");
			String wcsGridOffsets = request.getParameter("wcsgridoffsets");
			String wcsBoundingBox = request.getParameter("wcsboundingbox");
			String wcsResampleFactor = request
					.getParameter("wcsresamplefactor");
			String wcsResmapleFilter = request
					.getParameter("wcsresamplefilter");

			// Either "thredds" or "wcs"
			String dataSetInterface = request.getParameter("datasetinterface");

			// Output
			String output = request.getParameter("outputtype");
			String outputFile = request.getParameter("outputfile");
			String userDirectory = request.getParameter("userdirectory");
			String groupById = request.getParameter("groupby");
			String delimId = request.getParameter("delim");

			// Email
			String email = request.getParameter("email");
			String finalUrlEmail = request.getParameter("finalurlemail");

			// Check to see if their user directory is still around.
			if (!FileHelper.doesDirectoryOrFileExist(System
					.getProperty("applicationUserSpaceDir")
					+ request.getParameter("userdirectory"))) {
				FileHelper.createDir(System
						.getProperty("applicationUserSpaceDir")
						+ request.getParameter("userdirectory"));
			}
			FileHelper.createUserDirectory(System
					.getProperty("applicationUserSpaceDir"));

			boolean categorical = false;

			// Check for upload directory. If not found, return null
			// TODO- return error
			String userspacePath = System
					.getProperty("applicationUserSpaceDir");
			String appTempDir = System.getProperty("applicationTempDir");
			String baseFilePath = appTempDir + FileHelper.getSeparator();
			File uploadDirectory = FileHelper
					.createFileRepositoryDirectory(baseFilePath);
			if (!uploadDirectory.exists()) {
				return;
			}

			String attributeName = attribute;
			String shapefilePath = null;
			FeatureCollection<SimpleFeatureType, SimpleFeature> featureCollection = null;

			// Define a feature collection isiong lat/lon or shapefile
			if (lat != null && lon != null) {

				String fullUserDir = userspacePath + userDirectory
						+ FileHelper.getSeparator();

				// When getting the geometry from the epa nhd service, there
				// won't be
				// any attributes. But the attribute cannot be null, so set it
				// to a
				// placeholder, "placeholder"
				attributeName = "placeholder";
				shapefilePath = fullUserDir + "latlon.shp";
				String shxFilePath = fullUserDir + "latlon.shx";

				try {
					featureCollection = NHDService.getGeometry(lon, lat,
							shapefilePath, shxFilePath);
				} catch (Exception ex) {
					XmlReplyBean xmlOutput = new XmlReplyBean(AckBean.ACK_FAIL,
							new ErrorBean(ex.getMessage()));
					XmlUtils.sendXml(xmlOutput, start, response);
					return;
				}
			} else {

				AvailableFilesBean afb = AvailableFilesBean
						.getAvailableFilesBean(appTempDir, userspacePath
								+ userDirectory);
				List<ShapeFileSetBean> shapeBeanList = afb.getShapeSetList();
				try {
					featureCollection = ShapefileToFeatureCollection
							.shapefileToFeatureCollection(uploadDirectory,
									outputFile, userDirectory, userspacePath,
									appTempDir, shapeSet, features, attribute);
				} catch (CQLException ex) {
					XmlReplyBean xmlOutput = new XmlReplyBean(AckBean.ACK_FAIL,
							new ErrorBean(ex.getMessage()));
					XmlUtils.sendXml(xmlOutput, start, response);
					return;
				}
			}

			DelimiterOption delimiterOption = null;
			if (delimId != null) {
				try {
					delimiterOption = DelimiterOption.valueOf(delimId);
				} catch (IllegalArgumentException e) { /* failure handled below */
				}
			}

			if (delimiterOption == null)
				delimiterOption = DelimiterOption.getDefault();

			if ("WCS".equals(dataSetInterface)) {
				File wcsRequestOutputFile = wcs(wcsServer, wcsCoverage,
						wcsBoundingBox, wcsGridCRS, wcsGridOffsets,
						wcsResmapleFilter, wcsResampleFactor);

				dataset = wcsRequestOutputFile.toURI().toURL().toString();
				dataTypes = new String[] { "I0B0" };
			}

			String fromTime = from;
			String toTime = to;

			DateFormat df = new SimpleDateFormat("yyyy-MM-dd");
			df.setTimeZone(TimeZone.getTimeZone("UTC"));
			Date toDate = new Date();
			Date fromDate = new Date();
			boolean parsedDates = false;
			if (toTime == null || fromTime == null) {
				toDate = null;
				fromDate = null;
				parsedDates = true;
			} else {
				try {
					toDate = df.parse(toTime);
					fromDate = df.parse(fromTime);
					parsedDates = true;
				} catch (ParseException e1) {
					parsedDates = false;
					LoggerFactory.getLogger(ProcessServlet.class).debug(
							e1.getMessage());
				}
			}

			if (!parsedDates) {
				// return some sort of error
			}

			String datasetUrl = dataset;
			Formatter errorLog = new Formatter();
			FeatureDataset featureDataset = FeatureDatasetFactoryManager.open(
					FeatureType.ANY, datasetUrl, null, errorLog);

			if (featureDataset.getFeatureType() == FeatureType.GRID
					&& featureDataset instanceof GridDataset) {
				try {
					grid(featureDataset, categorical, featureCollection,
							attributeName, delimiterOption, fromDate, toDate,
							dataTypes, groupById, outputStats, outputFile);
				} catch (Exception ex) {
					XmlReplyBean xmlOutput = new XmlReplyBean(AckBean.ACK_FAIL,
							new ErrorBean(ex.getMessage()));
					XmlUtils.sendXml(xmlOutput, start, response);
					return;
				}

			} else if (featureDataset.getFeatureType() == FeatureType.STATION
					&& featureDataset instanceof FeatureDatasetPoint) {
				try {
					station(featureDataset, featureCollection, fromDate,
							toDate, delimiterOption, dataTypes, groupById,
							outputFile);
				} catch (Exception ex) {
					XmlReplyBean xmlOutput = new XmlReplyBean(AckBean.ACK_FAIL,
							new ErrorBean(ex.getMessage()));
					XmlUtils.sendXml(xmlOutput, start, response);
					return;
				}
			}

			// Move completed file to the upload repository
			FileHelper.copyFileToFile(
					new File(System.getProperty("applicationWorkDir")
							+ outputFile), uploadDirectory.getPath(), true);

			File outputDataFile = new File(uploadDirectory.getPath(),
					outputFile);
			if (!outputDataFile.exists()) {
				XmlReplyBean xmlOutput = new XmlReplyBean(AckBean.ACK_FAIL,
						new ErrorBean("Unable to create output file."));
				XmlUtils.sendXml(xmlOutput, start, response);
				return;
			}

			System.out.println(outputDataFile.getAbsolutePath());
			FileLocationBean fileLocations = new FileLocationBean(
					outputDataFile.getName(), shapefilePath);

			// If user specified an E-Mail address, send an E-Mail to the user
			// with the provided link
			if (email != null && !"".equals(email)) {
				try {
					sendEmail(email, finalUrlEmail);
				} catch (AddressException ex) {
					XmlReplyBean xmlOutput = new XmlReplyBean(
							AckBean.ACK_FAIL,
							new ErrorBean(
									ErrorBean.ERR_EMAIL_ERROR_INCORRECT_ADDRESS));
					XmlUtils.sendXml(xmlOutput, start, response);
					return;
				} catch (MessagingException ex) {
					XmlReplyBean xmlOutput = new XmlReplyBean(AckBean.ACK_FAIL,
							new ErrorBean(ErrorBean.ERR_EMAIL_ERROR));
					XmlUtils.sendXml(xmlOutput, start, response);
					return;
				}
			}

			// We are, for the moment, assuming there is a file at this location
			// The link is sent out as just the file name. When the user sends
			// the request
			// back, we go to the directory at String baseFilePath =
			// System.getProperty("applicationTempDir");
			// + the file specified by the user ((fileForUpload.getName()) and
			// we send that
			// back to the user
			XmlReplyBean xmlReply = new XmlReplyBean(AckBean.ACK_OK,
					fileLocations);
			XmlUtils.sendXml(xmlReply, start, response);
			return;
		}

	}

	/**
	 * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse
	 *      response)
	 */
	@Override
	protected void doPost(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {
		doGet(request, response);
	}

	private void grid(
			FeatureDataset featureDataset, boolean categorical,
			FeatureCollection<SimpleFeatureType, SimpleFeature> featureCollection,
			String attributeName, DelimiterOption delimiterOption,
			Date fromDate, Date toDate, String[] dataTypes, String groupById,
			String[] outputStats, String outputFile)
	throws IOException, SchemaException, TransformException,
			IllegalArgumentException, FactoryException, InvalidRangeException {

		GridDataset gridDataset = (GridDataset) featureDataset;
		String gridName = dataTypes[0];
		GridDatatype gdt = gridDataset.findGridByName(gridName);
		categorical = gdt.getDataType().isIntegral();
		if (categorical) {
			BufferedWriter writer = null;
			try {
				writer = new BufferedWriter(new FileWriter(new File(
						System.getProperty("applicationWorkDir"), outputFile)));
				// *** long running task ***
				FeatureCategoricalGridCoverage.execute(featureCollection,
						attributeName, gridDataset, gridName, writer,
						delimiterOption.delimiter);
			} finally {
				if (writer != null) {
					try {
						writer.close();
					} catch (IOException e) {
						/* get bent */
					}
				}
			}
		} else {
			try {
				Range timeRange = null;
				try {
					CoordinateAxis1DTime timeAxis = gdt.getCoordinateSystem()
							.getTimeAxis1D();
					int timeIndexMin = 0;
					int timeIndexMax = 0;
					if (fromDate != null && toDate != null) {
						timeIndexMin = timeAxis.findTimeIndexFromDate(fromDate);
						timeIndexMax = timeAxis.findTimeIndexFromDate(toDate);
						timeRange = new Range(timeIndexMin, timeIndexMax);
					}
				} catch (NumberFormatException e) {
					LoggerFactory.getLogger(ProcessServlet.class).error(
							e.getMessage());
				} catch (InvalidRangeException e) {
					LoggerFactory.getLogger(ProcessServlet.class).error(
							e.getMessage());
				}
				GroupBy.GridOption groupBy = null;
				if (groupById != null) {
					try {
						groupBy = GroupBy.GridOption.valueOf(groupById);
					} catch (IllegalArgumentException e) {
						/* failure handled below */
					}
				}
				if (groupBy == null) {
					groupBy = GroupBy.GridOption.getDefault();
				}
				List<Statistic> statisticList = new ArrayList<Statistic>();
				if (outputStats != null && outputStats.length > 0) {
					for (int i = 0; i < outputStats.length; ++i) {
						// may throw exception if outputStats value doesn't
						// map to Statistic enum value, ivan says let percolate
						// up.
						statisticList.add(Statistic.valueOf(outputStats[i]));
					}
				}
				if (statisticList.isEmpty()) {
					throw new IllegalArgumentException(
							"no output statistics selected");
				}
				BufferedWriter writer = null;
				try {
					writer = new BufferedWriter(new FileWriter(new File(
							System.getProperty("applicationWorkDir"),
							outputFile)));
					// *** long running task ***
					FeatureCoverageWeightedGridStatistics.execute(
							featureCollection, attributeName, gridDataset,
							gridName, timeRange, statisticList, writer,
							groupBy == GroupBy.GridOption.statistics,
							delimiterOption.delimiter);
				} finally {
					if (writer != null) {
						try {
							writer.close();
						} catch (IOException e) {
							/* get bent */
						}
					}
				}
			} finally {
				try {
					if (gridDataset != null) {
						gridDataset.close();
					}
				} catch (IOException e) {
					/* get bent */
				}
			}
		}
	}

	private void station(
			FeatureDataset featureDataset,
			FeatureCollection<SimpleFeatureType, SimpleFeature> featureCollection,
			Date fromDate, Date toDate, DelimiterOption delimiterOption,
			String[] dataTypes, String groupById, String outputFile)
	throws FactoryException, SchemaException,
			org.opengis.coverage.grid.InvalidRangeException,
			TransformException, IOException {

		FeatureDatasetPoint fdp = (FeatureDatasetPoint) featureDataset;
		List<ucar.nc2.ft.FeatureCollection> fcl = fdp
				.getPointFeatureCollectionList();
		if (fcl != null && fcl.size() == 1) {
			ucar.nc2.ft.FeatureCollection fc = fcl.get(0);
			if (fc != null && fc instanceof StationTimeSeriesFeatureCollection) {
				StationTimeSeriesFeatureCollection stsfc = (StationTimeSeriesFeatureCollection) fc;
				List<VariableSimpleIF> variableList = new ArrayList<VariableSimpleIF>();
				for (String variableName : dataTypes) {
					VariableSimpleIF variable = featureDataset
							.getDataVariable(variableName);
					if (variable != null) {
						variableList.add(variable);
					} else {
						// do we care?
					}
				}
				GroupBy.StationOption groupBy = null;
				if (groupById != null) {
					try {
						groupBy = GroupBy.StationOption.valueOf(groupById);
					} catch (IllegalArgumentException e) {
						/* failure handled below */
					}
				}
				if (groupBy == null) {
					groupBy = GroupBy.StationOption.getDefault();
				}
				BufferedWriter writer = null;
				try {
					writer = new BufferedWriter(new FileWriter(new File(
							System.getProperty("applicationWorkDir"),
							outputFile)));
					StationDataCSVWriter.write(featureCollection, stsfc,
							variableList, new DateRange(fromDate, toDate),
							writer, groupBy == StationOption.variable,
							delimiterOption.delimiter);
				} finally {
					if (writer != null) {
						try {
							writer.close();
						} catch (IOException e) {
							/* swallow, don't mask exception */
						}
					}
				}
			} else {
				// wtf? I am gonna punch Ivan...
			}
		} else {
			// error, what do we do when more than one FeatureCollection? does
			// this happen? If yes, punch Ivan.
		}
	}

	private File wcs(String wcsServer, String wcsCoverage,
			String wcsBoundingBox, String wcsGridCRS, String wcsGridOffsets,
			String wcsResmapleFilter, String wcsResampleFactor)
	throws MalformedURLException, IOException {

		int wcsRequestHashCode = 7;
		wcsRequestHashCode = 31 * wcsRequestHashCode
				+ (wcsServer == null ? 0 : wcsServer.hashCode());
		wcsRequestHashCode = 31 * wcsRequestHashCode
				+ (wcsCoverage == null ? 0 : wcsCoverage.hashCode());
		wcsRequestHashCode = 31 * wcsRequestHashCode
				+ (wcsBoundingBox == null ? 0 : wcsBoundingBox.hashCode());
		wcsRequestHashCode = 31 * wcsRequestHashCode
				+ (wcsGridCRS == null ? 0 : wcsGridCRS.hashCode());
		wcsRequestHashCode = 31 * wcsRequestHashCode
				+ (wcsGridOffsets == null ? 0 : wcsGridOffsets.hashCode());
		wcsRequestHashCode = 31
				* wcsRequestHashCode
				+ (wcsResmapleFilter == null ? 0 : wcsResmapleFilter.hashCode());
		wcsRequestHashCode = 31
				* wcsRequestHashCode
				+ (wcsResampleFactor == null ? 0 : wcsResampleFactor.hashCode());
		File wcsRequestOutputFile = new File(
				System.getProperty("applicationWorkDir"),
				Integer.toHexString(wcsRequestHashCode) + ".tiff");
		FileOutputStream fos = null;
		InputStream coverageIStream = null;
		try {
			// Create WCS request
			String getCoverageRequest = wcsServer + "?service=WCS"
					+ "&version=1.1.1" + "&request=GetCoverage"
					+ "&identifier=" + wcsCoverage + "&boundingBox="
					+ wcsBoundingBox + "," + wcsGridCRS + "&gridBaseCRS="
					+ wcsGridCRS + "&gridOffsets=" + wcsGridOffsets
					+ "&format=image/GeoTIFF";
			// Call getCoverage
			HttpURLConnection httpConnection = HTTPUtils.openHttpConnection(
					new URL(getCoverageRequest), "GET");
			coverageIStream = HTTPUtils
					.getHttpConnectionInputStream(httpConnection);
			Map<String, List<String>> headerFields = HTTPUtils
					.getHttpConnectionHeaderFields(httpConnection);
			// TODO: check for error response from server
			String boundaryString = null;
			String[] contentType = headerFields.get("Content-Type").get(0)
					.split(" *; *");
			for (int i = 0; i < contentType.length; i++) {
				String[] field = contentType[i].split("=");
				if ("boundary".equals(field[0])) {
					boundaryString = field[1].substring(1,
							field[1].length() - 1); // remove quotes
				}
			}
			if (boundaryString == null) {
				// TODO: probably change exception thrown
				throw new IOException();
			}
			int part = 0;
			// find second part (coverage) of multi-part response
			while (part < 2) {
				String s = readLine(coverageIStream);
				if (s == null) {
					throw new IOException();
				}
				if (("--" + boundaryString).equals(s)) {
					part++;
				}
			}
			// actual coverage starts immediately after blank line
			String line;
			do {
				line = readLine(coverageIStream);
			} while (!"".equals(line));
			// write coverage bytes to file
			fos = new FileOutputStream(wcsRequestOutputFile);
			String closeTag = "--" + boundaryString + "--\n";
			byte[] b = new byte[1024];
			int bytesRead;
			while ((bytesRead = coverageIStream.read(b, 0, 1024)) > 0) {
				String endString = new String(Arrays.copyOfRange(b, bytesRead
						- closeTag.length(), bytesRead));
				if (closeTag.equals(endString)) {
					// Don't write close tag to file
					fos.write(b, 0, bytesRead - closeTag.length());
					break;
				}
				fos.write(b, 0, bytesRead);
			}
		} finally {
			if (fos != null) {
				try {
					fos.close();
				} catch (IOException e) {
					/* don't care, unrecoverable */
				}
			}
			if (coverageIStream != null) {
				try {
					coverageIStream.close();
				} catch (IOException e) {
					/* don't care, unrecoverable */
				}
			}
		}

		return wcsRequestOutputFile;

	}

	private String readLine(InputStream is) throws IOException {

		StringBuilder sb = new StringBuilder();

		int b;
		while ((b = is.read()) != -1) {

			char c = (char) b; // TODO: convert properly

			if (c == '\n') {
				return sb.toString();
			} else {
				sb.append(c);
			}
		}

		return null; // new line not found
	}

	private static final long serialVersionUID = 1L;

	/**
	 * @see HttpServlet#HttpServlet()
	 */
	public ProcessServlet() {
		super();
	}

	private boolean sendEmail(String email, String finalUrlEmail)
			throws AddressException, MessagingException {
		String content = "Your file is ready: " + finalUrlEmail;
		String subject = "Your file is ready";
		String from = "gdp_data@usgs.gov";
		EmailMessageBean emBean = new EmailMessageBean(from, email,
				new ArrayList<String>(), subject, content);
		EmailHandler emh = new EmailHandler();
		return emh.sendMessage(emBean);
	}
}