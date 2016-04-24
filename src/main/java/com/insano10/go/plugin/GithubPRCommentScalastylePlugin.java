package com.insano10.go.plugin;

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.insano10.go.plugin.analysis.scalastyle.ScalastyleResultsAnalyser;
import com.insano10.go.plugin.comment.GitHubPullRequestCommenter;
import com.insano10.go.plugin.settings.PluginSettingsProvider;
import com.thoughtworks.go.plugin.api.GoApplicationAccessor;
import com.thoughtworks.go.plugin.api.GoPlugin;
import com.thoughtworks.go.plugin.api.GoPluginIdentifier;
import com.thoughtworks.go.plugin.api.annotation.Extension;
import com.thoughtworks.go.plugin.api.exceptions.UnhandledRequestTypeException;
import com.thoughtworks.go.plugin.api.request.GoPluginApiRequest;
import com.thoughtworks.go.plugin.api.response.DefaultGoApiResponse;
import com.thoughtworks.go.plugin.api.response.DefaultGoPluginApiResponse;
import com.thoughtworks.go.plugin.api.response.GoPluginApiResponse;
import com.thoughtworks.go.plugin.api.task.JobConsoleLogger;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static java.util.Collections.singletonList;

@Extension
public class GithubPRCommentScalastylePlugin implements GoPlugin
{
    private final GitHubPullRequestCommenter pullRequestCommenter;
    private final ScalastyleResultsAnalyser scalastyleResultsAnalyser;
    private JobConsoleLogger consoleLogger;

    public GithubPRCommentScalastylePlugin()
    {
        this.pullRequestCommenter = new GitHubPullRequestCommenter(PluginSettingsProvider.getPluginSettings());
        this.scalastyleResultsAnalyser = new ScalastyleResultsAnalyser();
    }

    public GithubPRCommentScalastylePlugin(final GitHubPullRequestCommenter pullRequestCommenter,
                                           final ScalastyleResultsAnalyser scalastyleResultsAnalyser,
                                           final JobConsoleLogger consoleLogger)
    {
        this.pullRequestCommenter = pullRequestCommenter;
        this.scalastyleResultsAnalyser = scalastyleResultsAnalyser;
        this.consoleLogger = consoleLogger;
    }

    @Override
    public void initializeGoApplicationAccessor(GoApplicationAccessor goApplicationAccessor)
    {
    }

    @Override
    public GoPluginApiResponse handle(GoPluginApiRequest request) throws UnhandledRequestTypeException
    {
        switch (request.requestName())
        {
            case PluginConstants.REQUEST_NAME_GET_TASK_CONFIGURATION:
                return handleGetConfigRequest();
            case PluginConstants.REQUEST_NAME_GET_TASK_VIEW:
                return handleTaskView();
            case PluginConstants.REQUEST_NAME_VALIDATE_CONFIGURATION:
                return validateConfiguration(request);
            case PluginConstants.REQUEST_NAME_EXECUTE:
                return executeTask(request);
            default:
                throw new UnhandledRequestTypeException(request.requestName());
        }
    }

    @Override
    public GoPluginIdentifier pluginIdentifier()
    {
        return new GoPluginIdentifier(PluginConstants.TASK_PLUGIN_EXTENSION, singletonList(PluginConstants.TASK_PLUGIN_EXTENSION_VERSION));
    }

    private GoPluginApiResponse handleGetConfigRequest()
    {
        return DefaultGoPluginApiResponse.success("{" +
                                                      "  \"resultXmlFileLocation\": {" +
                                                      "    \"default-value\": \"target/scalastyle-result.xml\"," +
                                                      "    \"secure\": false," +
                                                      "    \"required\": true" +
                                                      "  }," +
                                                      "  \"artifactXmlFileLocation\": {" +
                                                      "    \"default-value\": \"\"," +
                                                      "    \"secure\": false," +
                                                      "    \"required\": true" +
                                                      "  }" +
                                                      "}");
    }

    private GoPluginApiResponse handleTaskView()
    {
        int responseCode = DefaultGoApiResponse.SUCCESS_RESPONSE_CODE;
        HashMap view = new HashMap();
        view.put("displayValue", "Github PR Comment: Scalastyle");
        try
        {
            view.put("template", IOUtils.toString(getClass().getResourceAsStream("/views/task.template.html"), "UTF-8"));
        }
        catch (Exception e)
        {
            responseCode = DefaultGoApiResponse.INTERNAL_ERROR;
            String errorMessage = "Failed to find template: " + e.getMessage();
            view.put("exception", errorMessage);
            logger().printLine("[Error]: " + errorMessage + " -> " + e.toString());
        }
        return renderJSON(responseCode, view);
    }

    private GoPluginApiResponse validateConfiguration(final GoPluginApiRequest request)
    {
        return DefaultGoPluginApiResponse.success("{\"errors\": {}}");
    }

    private GoPluginApiResponse executeTask(final GoPluginApiRequest request)
    {
        logger().printLine("Task executing...");
        logger().printLine("Request parameters: " + request.requestBody());

        try
        {
            final Gson GSON = new GsonBuilder().create();
            final Map dataMap = GSON.fromJson(request.requestBody(), Map.class);

            final Map configData = (Map) dataMap.get("config");
            final Map contextData = (Map) dataMap.get("context");
            final Map envVarsData = (Map) contextData.get("environmentVariables");

            final Map resultsFileData = (Map) configData.get("resultXmlFileLocation");
            final String workingDirectory = (String) contextData.get("workingDirectory");
            final String resultXmlFileLocation = (String) resultsFileData.get("value");
            final String fullResultsXmlFileLocation = workingDirectory + "/" + resultXmlFileLocation;

            final Map resultsArtifactData = (Map) configData.get("artifactXmlFileLocation");
            final String resultArtifactFileLocation = (String) resultsArtifactData.get("value");
            final String fullResultsArtifactLocation = resultArtifactFileLocation + "/" + getResultsFileName(resultXmlFileLocation);

            final String pipelineName = (String) envVarsData.get("GO_PIPELINE_NAME");
            final String pipelineLabel = (String) envVarsData.get("GO_PIPELINE_LABEL");
            final String stageName = (String) envVarsData.get("GO_STAGE_NAME");
            final String stageCounter = (String) envVarsData.get("GO_STAGE_COUNTER");
            final String jobName = (String) envVarsData.get("GO_JOB_NAME");

            final String serverBaseURLToUse = System.getProperty("go.plugin.github.pr.comment.go-server", "http://localhost:8153");

            final String trackbackLink = String.format("%s/go/files/%s/%s/%s/%s/%s/%s",
                                                       serverBaseURLToUse, pipelineName, pipelineLabel, stageName, stageCounter, jobName, fullResultsArtifactLocation);

            if (taskWasTriggeredFromPullRequest(envVarsData))
            {
                final Path resultsFilePath = Paths.get(fullResultsXmlFileLocation);

                if (Files.exists(resultsFilePath))
                {
                    final String summary = scalastyleResultsAnalyser.buildGithubMarkdownSummary(resultsFilePath, trackbackLink);


                    final String url = (String) envVarsData.get(getKeyLike(envVarsData, "^GO_SCM.*PRS_URL$"));
                    final int pullRequestId = Integer.parseInt((String) envVarsData.get(getKeyLike(envVarsData, "^GO_SCM.*PRS_PR_ID$")));

                    pullRequestCommenter.addCommentToPullRequest(url, pullRequestId, summary);

                    logger().printLine("Summary successfully added to " + url + ", PR ID [" + pullRequestId + "]");
                }
                else
                {
                    logger().printLine("No Scalastyle results found at " + resultsFilePath.toAbsolutePath());
                }
            }
            else
            {
                logger().printLine("Task was not triggered from a pull request. Aborting.");
                logger().printLine("This task is only compatible with the Github material (that builds pull requests) not the standard Git material");
            }

            logger().printLine("Task execution complete.");
            return renderJSON(HttpStatus.SC_OK, ImmutableMap.of("success", true, "message", "Task execution complete"));
        }
        catch (Exception e)
        {
            logger().printLine("[Error]: Error -> " + e.toString());
            return renderJSON(HttpStatus.SC_INTERNAL_SERVER_ERROR, ImmutableMap.of("success", false, "message", e.toString()));
        }
    }

    private String getResultsFileName(final String resultsXmlFileLocation)
    {
        final String[] tokens = resultsXmlFileLocation.split("/");
        return tokens[tokens.length - 1];
    }

    private boolean taskWasTriggeredFromPullRequest(final Map<String, Object> taskEnvironmentVariables)
    {
        //the GO_SCM keys are only present if the task was triggered from a PR
        return getKeyLike(taskEnvironmentVariables, "^GO_SCM.*PRS_URL$") != null;
    }

    private String getKeyLike(final Map<String, Object> map, final String regex)
    {
        for (String key : map.keySet())
        {
            if (key.matches(regex))
            {
                return key;
            }
        }
        return null;
    }

    private GoPluginApiResponse renderJSON(final int responseCode, Map response)
    {
        final String responseBody = new GsonBuilder().create().toJson(response);
        final DefaultGoPluginApiResponse apiResponse = new DefaultGoPluginApiResponse(responseCode);

        apiResponse.setResponseBody(responseBody);
        return apiResponse;
    }

    private JobConsoleLogger logger()
    {
        //horrible hack to lazily load the logger after a context magically appears from somewhere
        if (this.consoleLogger == null)
        {
            this.consoleLogger = JobConsoleLogger.getConsoleLogger();
        }
        return this.consoleLogger;
    }
}
