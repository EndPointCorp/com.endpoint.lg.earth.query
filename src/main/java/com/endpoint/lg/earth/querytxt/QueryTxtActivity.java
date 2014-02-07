/*
 * Copyright (C) 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.endpoint.lg.earth.querytxt;

import interactivespaces.activity.impl.ros.BaseRoutableRosActivity;
import interactivespaces.util.InteractiveSpacesUtilities;
import interactivespaces.util.data.json.JsonNavigator;
import interactivespaces.util.io.FileSupport;
import interactivespaces.util.io.FileSupportImpl;

import com.google.common.base.Strings;

import com.endpoint.lg.support.domain.Location;
import com.endpoint.lg.support.domain.Orientation;
import com.endpoint.lg.support.message.DomainMessages;
import com.endpoint.lg.support.message.MessageFields;
import com.endpoint.lg.support.message.MessageWrapper;
import com.endpoint.lg.support.message.querytxt.MessageTypesQueryTxt;

import java.io.File;
import java.util.Map;

/**
 * An Interactive Spaces activity which listens on a route for query.txt type
 * messages and writes the query.txt file for Google Earth.
 *
 * @author Keith M. Hughes
 */
public class QueryTxtActivity extends BaseRoutableRosActivity {

  /**
   * Configuration parameter of where to write the querytxt file.
   */
  public static final String CONFIGURATION_NAME_QUERYTXT_LOCATION = "lg.earth.querytxt.location";

  /**
   * The actual query text file to be written to.
   */
  private File queryTxtFile;

  /**
   * The directory where query text files will be written.
   */
  private File queryTxtFileDirectory;

  /**
   * The number of times to try writing the query file if one already exists.
   */
  private final int numberQueryWriteRetries = 5;

  /**
   * The amount of time between retries for trying to write the query file if
   * one exists. In milliseconds.
   */
  private final int queryWriteRetryDelay = 1000;

  /**
   * The file support to use for file operations.
   */
  private final FileSupport fileSupport = FileSupportImpl.INSTANCE;

  @Override
  public void onActivitySetup() {

    queryTxtFile = new File(getConfiguration().getRequiredPropertyString(CONFIGURATION_NAME_QUERYTXT_LOCATION));
    queryTxtFileDirectory = queryTxtFile.getParentFile();
    fileSupport.directoryExists(queryTxtFileDirectory);
  }

  @Override
  public void onNewInputJson(String channelName, Map<String, Object> m) {
    getLog().info("Got message " + m);
    JsonNavigator message = new JsonNavigator(m);

    String operation = message.getString(MessageWrapper.MESSAGE_FIELD_TYPE);
    message.down(MessageWrapper.MESSAGE_FIELD_DATA);
    if (MessageTypesQueryTxt.MESSAGE_TYPE_QUERYTXT_FLYTO.equals(operation)) {
      handleFlyToOperation(message);
    } else if (MessageTypesQueryTxt.MESSAGE_TYPE_QUERYTXT_SEARCH.equals(operation)) {
      handleSearchOperation(message);
    } else if (MessageTypesQueryTxt.MESSAGE_TYPE_QUERYTXT_TOUR.equals(operation)) {
      handleTourOperation(message);
    } else if (MessageTypesQueryTxt.MESSAGE_TYPE_QUERYTXT_PLANET.equals(operation)) {
      handlePlanetOperation(message);
    } else {
      getLog().warn(String.format("Unknown Google Earth operation %s", operation));
    }
  }

  /**
   * Handle a FlyTo operation.
   *
   * @param message
   *          the message data
   */
  private void handleFlyToOperation(JsonNavigator message) {
    String type = message.getString(MessageTypesQueryTxt.MESSAGE_FIELD_QUERYTXT_FLYTO_TYPE);

    StringBuilder query = new StringBuilder("flytoview=");

    String typeElementClose;
    if (MessageTypesQueryTxt.MESSAGE_FIELD_VALUE_FLYTO_TYPE_CAMERA.equals(type)) {
      query.append("<Camera>");
      typeElementClose = "</Camera>";
    } else if (MessageTypesQueryTxt.MESSAGE_FIELD_VALUE_FLYTO_TYPE_LOOKAT.equals(type)) {
      query.append("<LookAt>");
      typeElementClose = "</LookAt>";
    } else {
      getLog().warn(String.format("flyto had unknown type %s", type));
      return;
    }

    Location location = DomainMessages.deserializeLocation(message);

    query.append("<latitude>").append(location.getLatitude()).append("</latitude>");
    query.append("<longitude>").append(location.getLongitude()).append("</longitude>");
    query.append("<altitude>").append(location.getAltitude()).append("</altitude>");

    Orientation orientation = DomainMessages.deserializeOrientation(message);

    Double heading = orientation.getHeading();
    if (heading != null) {
      query.append("<heading>").append(heading).append("</heading>");
    }

    Double tilt = orientation.getTilt();
    if (tilt != null) {
      query.append("<tilt>").append(tilt).append("</tilt>");
    }

    Double roll = orientation.getRoll();
    if (roll != null) {
      query.append("<roll>").append(roll).append("</roll>");
    }

    message.up();

    if ("lookat".equals(type)) {
      Double range = message.getDouble("range");

      query.append("<range>").append(range).append("</range>");

    }

    String altitudeMode = message.getString("altitudeMode");
    if (!Strings.isNullOrEmpty(altitudeMode)) {
      query.append("<gx:altitudeMode>").append(altitudeMode).append("</gx:altitudeMode>");
    }

    String viewerOption = message.getString("viewerOption");
    if (!Strings.isNullOrEmpty(viewerOption)) {
      query.append("<gx:viewerOption>").append(viewerOption).append("</gx:viewerOption>");
    }

    query.append(typeElementClose);
    writeQuery(query);
  }

  /**
   * Handle a search operation.
   *
   * @param message
   *          the message data
   */
  private void handleSearchOperation(JsonNavigator message) {
    StringBuilder query = new StringBuilder();

    String q = message.getString(MessageTypesQueryTxt.MESSAGE_FIELD_QUERYTXT_SEARCH_QUERY);
    if (!Strings.isNullOrEmpty(q)) {
      query.append("search=").append(q);
      writeQuery(query);
    } else {
      Double latitude = message.getDouble(MessageFields.MESSAGE_FIELD_LOCATION_LATITUDE);
      Double longitude = message.getDouble(MessageFields.MESSAGE_FIELD_LOCATION_LONGITUDE);

      if (latitude != null && longitude != null) {
        query.append("search=").append(latitude).append(",").append(longitude);

        String label = message.getString(MessageTypesQueryTxt.MESSAGE_FIELD_QUERYTXT_SEARCH_LABEL);
        if (!Strings.isNullOrEmpty(label)) {
          query.append("(").append(label).append(")");
        }

        writeQuery(query);
      } else {
        getLog().warn("Search message either has no query or is missing either latitude or longitude");
      }
    }

  }

  /**
   * A tour message has come in.
   *
   * @param message
   *          the message data
   */
  private void handleTourOperation(JsonNavigator message) {
    StringBuilder query = new StringBuilder();

    boolean play = message.getBoolean(MessageTypesQueryTxt.MESSAGE_FIELD_QUERYTXT_TOUR_PLAY);
    if (play) {
      String tourName = message.getString(MessageTypesQueryTxt.MESSAGE_FIELD_QUERYTXT_TOUR_TOURNAME);
      if (!Strings.isNullOrEmpty(tourName)) {
        query.append("playtour=").append(tourName);
        writeQuery(query);
      } else {
        getLog().warn("Tour message had no tour name");
      }
    } else {
      query.append("exittour=true");
      writeQuery(query);
    }
  }

  /**
   * Handle the planet operation.
   *
   * @param message
   *          the data for the message
   */
  private void handlePlanetOperation(JsonNavigator message) {
    String destination = message.getString(MessageTypesQueryTxt.MESSAGE_FIELD_QUERYTXT_PLANET_DESTINATION);
    if (destination != null) {
      StringBuilder query = new StringBuilder().append("planet=").append(destination);
      writeQuery(query);
    } else {
      getLog().warn("Planet message had no destination");
    }
  }

  /**
   * Write the query to the query.txt file.
   *
   * @param query
   *          the formatted query
   */
  private synchronized void writeQuery(StringBuilder query) {
    try {
      File newFile = File.createTempFile("query", "txt", queryTxtFileDirectory);

      fileSupport.writeFile(newFile, query.toString());

      int count = 0;
      while (count < numberQueryWriteRetries && queryTxtFile.exists()) {
        InteractiveSpacesUtilities.delay(queryWriteRetryDelay);
        count++;
      }

      if (!queryTxtFile.exists()) {
        newFile.renameTo(queryTxtFile);
      } else {
        getLog().warn(
            String.format("The file %s has existed for too long. Aborting write.", queryTxtFile.getAbsolutePath()));
      }
    } catch (Exception e) {
      getLog().error("Error while writing query.txt file", e);
    }
  }
}