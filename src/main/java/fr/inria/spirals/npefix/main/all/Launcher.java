package fr.inria.spirals.npefix.main.all;

import fr.inria.spirals.npefix.resi.CallChecker;
import fr.inria.spirals.npefix.resi.context.NPEFixExecution;
import fr.inria.spirals.npefix.resi.context.NPEOutput;
import fr.inria.spirals.npefix.resi.selector.DomSelector;
import fr.inria.spirals.npefix.resi.selector.RandomSelector;
import fr.inria.spirals.npefix.resi.selector.Selector;
import fr.inria.spirals.npefix.resi.strategies.Strategy;
import fr.inria.spirals.npefix.transformer.processors.BeforeDerefAdder;
import fr.inria.spirals.npefix.transformer.processors.ForceNullInit;
import fr.inria.spirals.npefix.transformer.processors.IfSplitter;
import fr.inria.spirals.npefix.transformer.processors.MethodEncapsulation;
import fr.inria.spirals.npefix.transformer.processors.TargetModifier;
import fr.inria.spirals.npefix.transformer.processors.TryRegister;
import fr.inria.spirals.npefix.transformer.processors.VarRetrieveAssign;
import fr.inria.spirals.npefix.transformer.processors.VarRetrieveInit;
import fr.inria.spirals.npefix.transformer.processors.VariableFor;
import org.apache.commons.io.FileUtils;
import org.junit.Test;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spoon.SpoonException;
import spoon.SpoonModelBuilder;
import spoon.processing.ProcessingManager;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.AnnotationFilter;
import spoon.support.QueueProcessingManager;
import utils.TestClassesFinder;
import utils.sacha.runner.main.TestRunner;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class Launcher {

    private final String[] sourcePath;
    private final String classpath;
    private final String sourceOutput;
    private final String binOutput;
    private final SpoonModelBuilder compiler;
    private spoon.Launcher spoon;

    private final Logger logger = LoggerFactory.getLogger(Launcher.class);

    public Launcher(String[] sourcePath, String sourceOutput, String binOutput, String classpath) {
        this.sourcePath = sourcePath;
        if(!File.pathSeparator.equals(classpath.charAt(classpath.length() - 1) + "")) {
            classpath = classpath + File.pathSeparator;
        }

        this.classpath = classpath + System.getProperty("java.class.path");
        this.sourceOutput = sourceOutput;
        this.binOutput = binOutput;
        this.compiler = init();
        spoon.setSourceOutputDirectory(sourceOutput);
        spoon.setBinaryOutputDirectory(binOutput);
    }

    /**
     *
     */
    public void instrument() {
        ProcessingManager p = new QueueProcessingManager(spoon.getFactory());

        //p.addProcessor(TernarySplitter.class.getCanonicalName());
        p.addProcessor(IfSplitter.class.getCanonicalName());
        p.addProcessor(ForceNullInit.class.getCanonicalName());
        p.addProcessor(BeforeDerefAdder.class.getCanonicalName());
        p.addProcessor(TargetModifier.class.getCanonicalName());
        p.addProcessor(TryRegister.class.getCanonicalName());
        p.addProcessor(VarRetrieveAssign.class.getCanonicalName());
        p.addProcessor(VarRetrieveInit.class.getCanonicalName());
        p.addProcessor(MethodEncapsulation.class.getCanonicalName());
        p.addProcessor(VariableFor.class.getCanonicalName());

        logger.debug("Start code instrumentation");

        Set<CtType<?>> allWithoutTest = getAllClasses();
        p.process(allWithoutTest);

        spoon.prettyprint();

        compiler.compile();
        logger.debug("End code instrumentation");
    }

    /**
     * Get all classes without tests
     * @return
     */
    private Set<CtType<?>> getAllClasses() {
        Set<CtType<?>> allWithoutTest = new HashSet<>();
        List<CtType<?>> allClasses = spoon.getFactory().Class().getAll();
        for (int i = 0; i < allClasses.size(); i++) {
            CtType<?> ctType = allClasses.get(i);
            if(ctType.getSimpleName().endsWith("Test")) {
                continue;
            }
            if(ctType.getPosition().getFile().getAbsolutePath().contains("/test/")) {
                continue;
            }
            // junit 4
            List<CtElement> elements = ctType.getElements(new AnnotationFilter<>(Test.class));
            if(elements.size() > 0) {
                continue;
            }
            // junit 3
            if(containsJunit(ctType.getSuperclass())) {
               continue;
            }
            allWithoutTest.add(ctType);
        }
        return allWithoutTest;
    }

    private boolean containsJunit(CtTypeReference<?> ctType) {
        if(ctType == null) {
            return false;
        }
        if(ctType.getQualifiedName().contains("junit")) {
            return true;
        }
        return containsJunit(ctType.getSuperclass());
    }

    private void copyResources() {
        File directory = new File(sourceOutput);
        if(!directory.exists()) {
            directory.mkdirs();
        }
        Collection resources = FileUtils.listFiles(directory, spoon.RESOURCES_FILE_FILTER, spoon.ALL_DIR_FILTER);
        Iterator var6 = resources.iterator();

        while(var6.hasNext()) {
            Object resource = var6.next();
            String resourceParentPath = ((File)resource).getParent();
            String packageDir = resourceParentPath.substring(directory.getPath().length());
            packageDir = packageDir.replace("/java", "").replace("/resources", "");
            String targetDirectory = this.binOutput + packageDir;

            try {
                FileUtils.copyFileToDirectory((File) resource, new File(targetDirectory));
            } catch (IOException var12) {
                throw new SpoonException(var12);
            }
        }
    }

    private SpoonModelBuilder init() {
        spoon = new spoon.Launcher();
        for (int i = 0; i < sourcePath.length; i++) {
            String s = sourcePath[i];
            if(s != null) {
                spoon.addInputResource(s);
            }
        }
        //spoon.addTemplateResource(new FileSystemFolder(new File("src/main/java/fr/inria/spirals/npefix/transformer/template")));

        SpoonModelBuilder compiler = spoon.getModelBuilder();
        compiler.setSourceClasspath(classpath.split(File.pathSeparator));

        spoon.getEnvironment().setCopyResources(true);
        spoon.getEnvironment().setAutoImports(true);
        spoon.getEnvironment().setShouldCompile(true);
        spoon.getEnvironment().setGenerateJavadoc(false);
        spoon.getEnvironment().setComplianceLevel(7);

        spoon.buildModel();
        copyResources();
        return compiler;
    }

    public NPEOutput run() {
        return run(new RandomSelector());
    }

    public NPEOutput run(Selector selector) {
        CallChecker.enable();
        CallChecker.strategySelector = selector;


        String[] sourceClasspath = spoon.getModelBuilder().getSourceClasspath();

        URLClassLoader urlClassLoader = getUrlClassLoader(sourceClasspath);

        CallChecker.currentClassLoader = urlClassLoader;

        String[] testsString = new TestClassesFinder().findIn(urlClassLoader, false);
        Class[] tests = filterTest(urlClassLoader, testsString);

        List<Method> methodTests = new ArrayList();
        for (int i = 0; i < tests.length; i++) {
            Class test = tests[i];
            Method[] methods = test.getDeclaredMethods();
            for (int j = 0; j < methods.length; j++) {
                Method method = methods[j];
                if(method.getReturnType().equals(void.class)
                        && method.getParameterTypes().length == 0) {
                    methodTests.add(method);
                }
            }
        }

        NPEOutput output = new NPEOutput();

        NPEFixExecution npeFixExecution = new NPEFixExecution(selector);

        TestRunner testRunner = new TestRunner();
        for (int i = 0; i < methodTests.size(); i++) {
            Method method = methodTests.get(i);
            Request request = Request.method(method.getDeclaringClass(), method.getName());

            npeFixExecution.setTest(method);
            CallChecker.currentExecution = npeFixExecution;
            Result result = testRunner.run(request);
            npeFixExecution.setTestResult(result);
            if(result.getRunCount() > 0) {
                output.add(npeFixExecution);
                if (selector.restartTest(npeFixExecution)) {
                    /*Map<Location, Integer> currentIndex = new HashMap<>(npeFixExecution.getCurrentIndex());
                    Map<Location, Map<String, Object>> possibleVariables = npeFixExecution
                            .getPossibleVariables();
                    Map<Location, List<Object>> possibleValues = npeFixExecution
                            .getPossibleValues();

                    Set<Location> locations = currentIndex.keySet();
                    for (Iterator<Location> iterator = locations
                            .iterator(); iterator.hasNext(); ) {
                        Location location = iterator.next();

                        if(possibleVariables != null
                                && possibleVariables.containsKey(location)
                                && possibleVariables.get(location).size() > currentIndex.get(location) + 1) {
                            currentIndex.put(location, currentIndex.get(location) + 1);
                        } else if ( possibleValues != null
                                && possibleValues.containsKey(location)
                                && possibleValues.get(location).size() > currentIndex.get(location) + 1) {
                            currentIndex.put(location, currentIndex.get(location) + 1);
                        }
                    }*/

                    npeFixExecution = new NPEFixExecution(selector);
                    /*npeFixExecution.setCurrentIndex(currentIndex);
                    npeFixExecution.setPossibleValues(possibleValues);
                    npeFixExecution.setPossibleVariables(possibleVariables);
                    i--;*/
                } else {
                    npeFixExecution = new NPEFixExecution(selector);
                }
            } else {
                npeFixExecution = new NPEFixExecution(selector);
            }
            CallChecker.enable();
            //CallChecker.cache.clear();
            CallChecker.getDecisions().clear();
        }
        Collections.sort(output);
        return output;
    }

    public NPEOutput runStrategy(Strategy strategy) {
        DomSelector.strategy = strategy;
        return run(new DomSelector());
    }

    public NPEOutput runStrategy(Strategy...strategies) {
        NPEOutput output = new NPEOutput();
        for (int i = 0; i < strategies.length; i++) {
            Strategy strategy = strategies[i];
            DomSelector.strategy = strategy;
            NPEOutput run = run(new DomSelector());
            output.addAll(run);
        }
        Collections.sort(output);
        return output;
    }

    private Class[] filterTest(URLClassLoader urlClassLoader, String[] testsString) {
        Set<Class> tests = new HashSet<>();
        for (int i = 0; i < testsString.length; i++) {
            String s = testsString[i];
            if(!isValidTest(s)) {
                continue;
            }
            try {
                Class<?> aClass = urlClassLoader.loadClass(s);
                tests.add(aClass);
            } catch (ClassNotFoundException e) {
                continue;
            }
        }
        return tests.toArray(new Class[]{});
    }

    public static URLClassLoader getUrlClassLoader(String[] sourceClasspath) {
        ArrayList<URL> uRLClassPath = new ArrayList<>();
        for (int i = 0; i < sourceClasspath.length; i++) {
            String s = sourceClasspath[i];
            try {
                uRLClassPath.add(new File(s).toURL());
            } catch (MalformedURLException e) {
                continue;
            }
        }
        return new URLClassLoader(uRLClassPath.toArray(new URL[]{}));
    }

    private boolean isValidTest(String testName) {
        return spoon.getFactory().Class().get(testName) != null;
    }

    public SpoonModelBuilder getCompiler() {
        return compiler;
    }
}