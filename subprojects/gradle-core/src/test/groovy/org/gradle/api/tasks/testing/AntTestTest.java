/*
 * Copyright 2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.tasks.testing;

import org.gradle.api.GradleException;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.ConventionTask;
import org.gradle.api.tasks.AbstractConventionTaskTest;
import org.gradle.api.tasks.util.ExistingDirsFilter;
import org.gradle.api.testing.detection.SetBuildingTestClassReceiver;
import org.gradle.api.testing.fabric.TestFramework;
import org.gradle.api.testing.fabric.TestFrameworkInstance;
import org.gradle.util.GFileUtils;
import org.gradle.util.GUtil;
import org.gradle.util.WrapUtil;
import static org.hamcrest.Matchers.*;
import org.jmock.Expectations;
import org.jmock.integration.junit4.JMock;
import org.jmock.integration.junit4.JUnit4Mockery;
import org.jmock.lib.legacy.ClassImposteriser;
import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.*;


/**
 * @author Hans Dockter
 */
@RunWith(JMock.class)
public class AntTestTest extends AbstractConventionTaskTest {
    static final String TEST_PATTERN_1 = "pattern1";
    static final String TEST_PATTERN_2 = "pattern2";
    static final String TEST_PATTERN_3 = "pattern3";

    private File classesDir;
    private File resultsDir;
    private File reportDir;

    private static final Set TEST_DEPENDENCY_MANAGER_CLASSPATH = WrapUtil.toSet(new File("jar1"));
    private List convertedClasspath;

    static final Set<String> okTestClassNames = new HashSet<String>(Arrays.asList("test.HumanTest", "test.CarTest"));

    private JUnit4Mockery context = new JUnit4Mockery() {{
        setImposteriser(ClassImposteriser.INSTANCE);
    }};

    TestFramework testFrameworkMock = context.mock(TestFramework.class);
    TestFrameworkInstance testFrameworkInstanceMock = context.mock(TestFrameworkInstance.class);
    SetBuildingTestClassReceiver testClassReceiverMock = context.mock(SetBuildingTestClassReceiver.class);

    private FileCollection classpathMock = context.mock(FileCollection.class);
    private ExistingDirsFilter existentDirsFilterMock = context.mock(ExistingDirsFilter.class);

    private AntTest test;
    private File classfile;

    @Before public void setUp() {
        super.setUp();
        test = createTask(AntTest.class);
        context.checking(new Expectations(){{
            one(testFrameworkMock).getInstance(test);
            will(returnValue(testFrameworkInstanceMock));
            one(testFrameworkInstanceMock).initialize(getProject(), test);
        }});
        test.useTestFramework(testFrameworkMock);
        test.setScanForTestClasses(false);

        File rootDir = getProject().getProjectDir();
        classesDir = new File(rootDir, "testClassesDir");
        classfile = new File(classesDir, "FileTest.class");
        GFileUtils.touch(classfile);
        resultsDir = new File(rootDir, "resultDir");
        reportDir = new File(rootDir, "report/tests");
        convertedClasspath = GUtil.addLists(WrapUtil.toList(classesDir), TEST_DEPENDENCY_MANAGER_CLASSPATH);
    }

    public ConventionTask getTask() {
        return test;
    }

    @org.junit.Test public void testInit() {
        assertNotNull(test.getTestFramework());
        assertNull(test.getTestClassesDir());
        assertNull(test.getClasspath());
        assertNull(test.getTestResultsDir());
        assertNull(test.getTestReportDir());
        assertEquals(WrapUtil.toLinkedSet(), test.getIncludes());
        assertEquals(WrapUtil.toLinkedSet(), test.getExcludes());
        assert test.isStopAtFailuresOrErrors();
    }

    @org.junit.Test
    public void testExecute() {
        setUpMocks(test);
        context.checking(new Expectations() {{
            one(testFrameworkInstanceMock).prepare(getProject(), test, testClassReceiverMock);
            one(testClassReceiverMock).getTestClassNames(); will(returnValue(okTestClassNames));
            one(testFrameworkInstanceMock).execute(getProject(), test, okTestClassNames, new ArrayList<String>());
            one(testFrameworkInstanceMock).report(getProject(), test);
        }});

        test.execute();
    }

    @org.junit.Test
    public void testExecuteWithoutReporting() {
        setUpMocks(test);
        test.setTestReport(false);
        context.checking(new Expectations() {{
            one(testFrameworkInstanceMock).prepare(getProject(), test, testClassReceiverMock);
            one(testClassReceiverMock).getTestClassNames(); will(returnValue(okTestClassNames));
            one(testFrameworkInstanceMock).execute(getProject(), test, okTestClassNames, new ArrayList<String>());
        }});

        test.execute();
    }

    public void testExecuteWithTestFailuresAndStopAtFailures() {
        setUpMocks(test);
        context.checking(new Expectations() {{
            one(testFrameworkInstanceMock).prepare(getProject(), test, testClassReceiverMock);
            one(testClassReceiverMock).getTestClassNames(); will(returnValue(okTestClassNames));
            one(testFrameworkInstanceMock).execute(getProject(), test, okTestClassNames, new ArrayList<String>());
        }});
        getProject().getAnt().setProperty(AntTest.FAILURES_OR_ERRORS_PROPERTY, "true");
        try {
            test.executeTests();
            fail();
        } catch (GradleException e) {
            assertThat(e.getMessage(), equalTo("??"));
        }
    }

    @org.junit.Test public void testExecuteWithTestFailuresAndContinueWithFailures() {
        setUpMocks(test);
        test.setStopAtFailuresOrErrors(false);
        context.checking(new Expectations() {{
            one(testFrameworkInstanceMock).prepare(getProject(), test, testClassReceiverMock);
            one(testClassReceiverMock).getTestClassNames(); will(returnValue(okTestClassNames));
            one(testFrameworkInstanceMock).execute(getProject(), test, okTestClassNames, new ArrayList<String>());
            one(testFrameworkInstanceMock).report(getProject(), test);
        }});
        getProject().getAnt().setProperty(AntTest.FAILURES_OR_ERRORS_PROPERTY, "true");
        test.execute();
    }

    private void setUpMocks(final AntTest test) {
        test.setTestClassesDir(classesDir);
        test.setTestResultsDir(resultsDir);
        test.setTestReportDir(reportDir);
        test.setClasspath(classpathMock);
        test.setTestClassReceiver(testClassReceiverMock);
        test.setTestSrcDirs(Collections.<File>emptyList());

        context.checking(new Expectations(){{
            allowing(testFrameworkInstanceMock).manualTestClass(with(aNonNull(String.class)));
        }});
    }

    @org.junit.Test public void testIncludes() {
        assertSame(test, test.include(TEST_PATTERN_1, TEST_PATTERN_2));
        assertEquals(WrapUtil.toLinkedSet(TEST_PATTERN_1, TEST_PATTERN_2), test.getIncludes());
        test.include(TEST_PATTERN_3);
        assertEquals(WrapUtil.toLinkedSet(TEST_PATTERN_1, TEST_PATTERN_2, TEST_PATTERN_3), test.getIncludes());
    }

    @org.junit.Test public void testExcludes() {
        assertSame(test, test.exclude(TEST_PATTERN_1, TEST_PATTERN_2));
        assertEquals(WrapUtil.toLinkedSet(TEST_PATTERN_1, TEST_PATTERN_2), test.getExcludes());
        test.exclude(TEST_PATTERN_3);
        assertEquals(WrapUtil.toLinkedSet(TEST_PATTERN_1, TEST_PATTERN_2, TEST_PATTERN_3), test.getExcludes());
    }
}
