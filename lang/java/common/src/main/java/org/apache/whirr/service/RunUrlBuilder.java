package org.apache.whirr.service;

import java.util.Iterator;
import java.util.List;
import static org.jclouds.scriptbuilder.domain.Statements.exec;

import org.jclouds.scriptbuilder.ScriptBuilder;

public class RunUrlBuilder {

  /**
     * add runurl scripts to the userdata shell script
     * @param runUrls List of all runurl scripts to run
     * @return bytearray containing the shell script
     */
    public static byte[] runUrls(List<RunUrlScript> runUrls) {
        ScriptBuilder scriptBuilder = new ScriptBuilder().addStatement(
                exec("wget -qO/usr/bin/runurl run.alestic.com/runurl")).addStatement(
                exec("chmod 755 /usr/bin/runurl"));

        for (Iterator<RunUrlScript> it = runUrls.iterator(); it.hasNext();) {
            RunUrlScript urlScript = it.next();

            scriptBuilder.addStatement(exec("runurl " + urlScript.getScript()));
        }

    return scriptBuilder.build(org.jclouds.scriptbuilder.domain.OsFamily.UNIX)
      .getBytes();
  }

}
