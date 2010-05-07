package org.apache.whirr.service;

/**
 * class for creating a runurl command
 * @param baseurl   the baseurl part e.g. cloudera-tom.s3.amazonaws.com/ may or may not contain a trailing slash
 * @param scriptPath    the path to the script that should be run by runurl relative to the baseurl
 * @param scriptArgs    an Array of strings that should be appended as arguments to the script
 * @return
 */

public class RunUrlScript {

    private String runUrlCommand;

    public RunUrlScript(String baseUrl, String scriptPath, String... scriptArgs) {
        if (baseUrl == null) {
            baseUrl = "cloudera-tom.s3.amazonaws.com/";
        }
        if (!baseUrl.endsWith("/")) {
            baseUrl.concat("/");
        }
        runUrlCommand = baseUrl + scriptPath;
        for (String arg : scriptArgs) {
            runUrlCommand.concat(" " + arg);
        }
    }

    public String getScript() {
        return runUrlCommand;
    }
}
