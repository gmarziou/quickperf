/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * Copyright 2019-2019 the original author or authors.
 */

package org.quickperf.junit5;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.quickperf.*;
import org.quickperf.config.library.QuickPerfConfigs;
import org.quickperf.config.library.QuickPerfConfigsLoader;
import org.quickperf.config.library.SetOfAnnotationConfigs;
import org.quickperf.issue.BusinessOrTechnicalIssue;
import org.quickperf.issue.PerfIssuesEvaluator;
import org.quickperf.issue.PerfIssuesToFormat;
import org.quickperf.perfrecording.PerformanceRecording;
import org.quickperf.reporter.QuickPerfReporter;
import org.quickperf.repository.BusinessOrTechnicalIssueRepository;
import org.quickperf.testlauncher.NewJvmTestLauncher;

import java.lang.reflect.Method;
import java.util.Collection;

public class QuickPerfTestExtension implements BeforeEachCallback, InvocationInterceptor {

    private final QuickPerfConfigs quickPerfConfigs =  QuickPerfConfigsLoader.INSTANCE.loadQuickPerfConfigs();

    private final PerformanceRecording performanceRecording = PerformanceRecording.INSTANCE;

    private final BusinessOrTechnicalIssueRepository businessOrTechnicalIssueRepository = BusinessOrTechnicalIssueRepository.INSTANCE;

    private final PerfIssuesEvaluator perfIssuesEvaluator = PerfIssuesEvaluator.INSTANCE;

    private final QuickPerfReporter quickPerfReporter = QuickPerfReporter.INSTANCE;

    private TestExecutionContext testExecutionContext;

    @Override
    public void beforeEach(ExtensionContext extensionContext) {
        int junit5AllocationOffset = 40;
        testExecutionContext = TestExecutionContext.buildFrom(quickPerfConfigs
                                                            , extensionContext.getRequiredTestMethod()
                                                            , junit5AllocationOffset);
    }

    @Override
    public void interceptTestMethod(Invocation<Void> invocation,
                                    ReflectiveInvocationContext<Method> invocationContext,
                                    ExtensionContext extensionContext) throws Throwable {

        if(SystemProperties.TEST_CODE_EXECUTING_IN_NEW_JVM.evaluate()) {
            Object[] args = invocationContext.getArguments().toArray();
            Object target = invocationContext.getTarget().orElse(null);
            Method method = makeAccessible(invocationContext.getExecutable());
            invocation.skip();//skip the invocation as we directly invoke the test method
            if (!testExecutionContext.isQuickPerfDisabled()) {
                performanceRecording.start(testExecutionContext);
            }
            try {
                //directly invoke the method to lower the interaction between JUnit, other extensions and QuickPerf.
                method.invoke(target, args);
            } finally {
                if (!testExecutionContext.isQuickPerfDisabled()) {
                    performanceRecording.stop(testExecutionContext);
                }
            }
            return;
        }

        BusinessOrTechnicalIssue businessOrTechnicalIssue = BusinessOrTechnicalIssue.NONE;

        if (testExecutionContext.testExecutionUsesTwoJVMs()) {
            NewJvmTestLauncher newJvmTestLauncher = NewJvmTestLauncher.INSTANCE;
            newJvmTestLauncher.run( invocationContext.getExecutable()
                    , testExecutionContext.getWorkingFolder()
                    , testExecutionContext.getJvmOptions()
                    , QuickPerfJunit5Core.class);

            //skip the invocation as the test method is invoked directly inside the 'newJvmTestLauncher'
            invocation.skip();

            WorkingFolder workingFolder = testExecutionContext.getWorkingFolder();
            businessOrTechnicalIssue = businessOrTechnicalIssueRepository.findFrom(workingFolder);
        }
        else {
            try{
                if(!testExecutionContext.isQuickPerfDisabled()){
                    performanceRecording.start(testExecutionContext);
                }
                invocation.proceed();
            }
            catch (Throwable throwable){
                businessOrTechnicalIssue = BusinessOrTechnicalIssue.buildFrom(throwable);
            }
            finally {
                if(!testExecutionContext.isQuickPerfDisabled()){
                    performanceRecording.stop(testExecutionContext);
                }
            }
        }

        SetOfAnnotationConfigs testAnnotationConfigs = quickPerfConfigs.getTestAnnotationConfigs();
        Collection<PerfIssuesToFormat> groupOfPerfIssuesToFormat = perfIssuesEvaluator.evaluatePerfIssues(testAnnotationConfigs, testExecutionContext);

        testExecutionContext.cleanResources();

        quickPerfReporter.report(businessOrTechnicalIssue
                               , groupOfPerfIssuesToFormat
                               , testExecutionContext);

    }

    @SuppressWarnings("deprecation")
    private Method makeAccessible(Method executable) {
        if(!executable.isAccessible()){
            executable.setAccessible(true);
        }
        return executable;
    }

}