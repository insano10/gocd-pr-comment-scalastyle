package com.insano10.go.plugin.analysis.scalastyle;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

public class ScalastyleResultsAnalyserTest
{
    private static final Path RESULTS_FILE_PATH = Paths.get("src/test/resources/scalastyle-result.xml");

    private final ScalastyleResultsAnalyser analyser = new ScalastyleResultsAnalyser();


    @Test
    public void shouldProduceMarkdownSummaryOfResults() throws Exception
    {
        final String summary = analyser.buildGithubMarkdownSummary(RESULTS_FILE_PATH);

        assertThat(summary, is("0 errors\n265 warnings\n0 info"));
    }
}