/**
 * Copyright (c) 2018, Sylvain Baudoin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.sbaudoin.sonar.plugins.ansible.rules;

import com.github.sbaudoin.sonar.plugins.ansible.settings.AnsibleSettings;
import org.sonar.api.batch.fs.FilePredicate;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.issue.NewIssue;
import org.sonar.api.batch.sensor.issue.NewIssueLocation;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import com.github.sbaudoin.sonar.plugins.ansible.checks.AnsibleCheckRepository;
import com.github.sbaudoin.sonar.plugins.yaml.languages.YamlLanguage;

import javax.annotation.Nullable;
import java.io.*;
import java.net.URI;
import java.util.*;

public abstract class AbstractAnsibleSensor implements Sensor {
    private static final Logger LOGGER = Loggers.get(AbstractAnsibleSensor.class);

    private final FileSystem fileSystem;
    private final FilePredicate mainFilesPredicate;
    private final Map<URI, Set<String>> allIssues = new HashMap<>();
    private final Set<InputFile> scannedFiles = new HashSet<>();


    protected AbstractAnsibleSensor(FileSystem fileSystem/*, Checks<Object> checks, FileLinesContextFactory fileLinesContextFactory*/) {
        this.fileSystem = fileSystem;
        this.mainFilesPredicate = fileSystem.predicates().and(
                fileSystem.predicates().hasType(InputFile.Type.MAIN),
                fileSystem.predicates().hasLanguage(YamlLanguage.KEY));
    }


    protected void executeWithAnsibleLint(SensorContext context, @Nullable List<String> extraAnsibleLintArgs) {
        LOGGER.debug("Ansible sensor executed with context: " + context);

        for (InputFile inputFile : fileSystem.inputFiles(mainFilesPredicate)) {
            LOGGER.debug("Analyzing file: " + inputFile.filename());
            scannedFiles.add(inputFile);

            // Build ansible-lint command
            List<String> command = new ArrayList<>();
            command.addAll(Arrays.asList(getAnsibleLintPath(context), "-p", "--nocolor"));
            if (extraAnsibleLintArgs != null) {
                command.addAll(extraAnsibleLintArgs);
            }
            command.add(new File(inputFile.uri()).getAbsolutePath());

            // Execute Ansible Lint and get a parsable output
            List<String> output = new ArrayList<>();
            List<String> error = new ArrayList<>();
            executeCommand(command, output, error);
            if (!error.isEmpty()) {
                LOGGER.warn("Errors happened during analysis:{}{}",
                        System.getProperty("line.separator"),
                        String.join(System.getProperty("line.separator"), error)
                );
            }

            LOGGER.debug(output.size() + " issue(s) found");
            // Parse output and register all issues: as ansible-lint processes only playbooks but returns issues related to
            // used roles, we need to save all issues first before being able to to get role issues and save them
            output.forEach(this::registerIssue);
        }

        // Save all found issues
        saveIssues(context);
    }

    protected String getAnsibleLintPath(SensorContext context) {
        Optional<String> path = context.config().get(AnsibleSettings.ANSIBLE_LINT_PATH_KEY);
        return (path.isPresent())?path.get():"ansible-lint";
    }

    /**
     * Executes a system command and write the standard and error outputs to the passed
     * <code>StringBuilder</code> if not <code>null</code>
     *
     * @param command the command to be executed
     * @param stdOut where the standard output is written to line by line
     * @param errOut where the error output is written to
     * @return the command exit code
     */
    protected int executeCommand(List<String> command, List<String> stdOut, List<String> errOut) {
        assert stdOut != null;
        assert errOut != null;

        LOGGER.debug("Executing command: {}", command);

        int status = 1;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            Process p = pb.start();

            // Read standard output
            LineInputReader stdOutputReader = new LineInputReader(p.getInputStream());
            stdOutputReader.start();
            // Get error output
            LineInputReader errOutputReader = new LineInputReader(p.getErrorStream());
            errOutputReader.start();

            status = p.waitFor();

            // Create standard output lines
            stdOut.addAll(stdOutputReader.getOutput());

            // Write error output if any
            errOut.addAll(errOutputReader.getOutput());
        } catch (InterruptedException|IOException e) {
            LOGGER.error("Error executing command", e);
        }
        return status;
    }

    protected void registerIssue(String rawIssue) {
        String[] tokens = rawIssue.split(":", 2);

        URI fileURI = new File(tokens[0]).toURI();

        if (!allIssues.containsKey(fileURI)) {
            allIssues.put(fileURI, new HashSet<>());
        }
        allIssues.get(fileURI).add(tokens[1]);
    }

    protected void saveIssues(SensorContext context) {
        for (InputFile inputFile : scannedFiles) {
            LOGGER.debug("Saving issues for {}", inputFile.uri());
            Set<String> issues = allIssues.getOrDefault(inputFile.uri(), new HashSet<>());
            for (String issue : issues) {
                String[] tokens = issue.split(":", 2);
                LOGGER.debug("  Saving issue: {}", issue);
                String[] ruleTokens = tokens[1].trim().split("\\] ", 2);
                saveIssue(context, inputFile, Integer.parseInt(tokens[0]), ruleTokens[0].replace("[E", ""), ruleTokens[1]);
            }
        }
    }

    /**
     * Saves the found issues in SonarQube
     *
     * @param context the context
     */
    protected void saveIssue(SensorContext context, InputFile inputFile, int line, String ruleId, String message) {
        RuleKey ruleKey = getRuleKey(context, ruleId);

        if (ruleKey == null) {
            LOGGER.debug("Rule " + ruleId + " ignored, not found in repository");
            return;
        }

        NewIssue newIssue = context.newIssue().forRule(ruleKey);
        NewIssueLocation location = newIssue.newLocation()
                .on(inputFile)
                .message(message)
                .at(inputFile.selectLine(line));
        newIssue.at(location).save();
        LOGGER.debug("Issue {} saved for {}", ruleId, inputFile.filename());
    }

    /**
     * Returns the {@code RuleKey} identified as the passed {@code ruleId} or {@code null} if no corresponding active
     * rule has been found
     *
     * @param context the sensor context (that contains the active rules)
     * @param ruleId the rule Id (corresponding to the searched {@code RuleKey})
     * @return the {@code RuleKey} or {@code null} if no active rule has been found
     */
    protected RuleKey getRuleKey(SensorContext context, String ruleId) {
        RuleKey key = AnsibleCheckRepository.getRuleKey(ruleId);
        return (context.activeRules().find(key) != null)?key:null;
    }


    private class LineInputReader extends Thread {
        private List<String> output = new ArrayList<>();
        private BufferedReader input;


        public LineInputReader(InputStream input) {
            assert input != null;
            this.input = new BufferedReader(new InputStreamReader(input));
        }

        @Override
        public void run() {
            String line;
            try {
                while ((line = input.readLine()) != null) {
                    output.add(line);
                    LOGGER.trace("Read from input: {}", line);
                }
            } catch (IOException e) {
                LOGGER.error("Cannot read input stream", e);
            } finally {
                try {
                    input.close();
                } catch (IOException e) {
                    LOGGER.error("Unknown error", e);
                }
            }
        }

        public List<String> getOutput() {
            return output;
        }
    }
}
