package com.certacota.engine.service.perf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class PerfReportWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(SerializationFeature.INDENT_OUTPUT);

    public static Path write(String scenario, Object reportData) throws IOException {
        Path reportDir = Paths.get("target/perf-reports");
        Files.createDirectories(reportDir);
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        Path reportFile = reportDir.resolve(scenario + "-" + timestamp + ".json");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(reportFile.toFile(), reportData);
        return reportFile;
    }
}
