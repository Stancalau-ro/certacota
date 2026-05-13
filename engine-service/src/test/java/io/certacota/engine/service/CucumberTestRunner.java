package io.certacota.engine.service;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "io.certacota.engine.service,io.certacota.engine.service.steps")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty,html:build/reports/cucumber.html")
public class CucumberTestRunner {
}
