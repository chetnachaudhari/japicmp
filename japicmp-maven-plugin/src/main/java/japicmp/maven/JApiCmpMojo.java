package japicmp.maven;

import com.google.common.base.Optional;
import japicmp.cli.JApiCli;
import japicmp.cmp.JarArchiveComparator;
import japicmp.cmp.JarArchiveComparatorOptions;
import japicmp.config.Options;
import japicmp.model.AccessModifier;
import japicmp.model.JApiChangeStatus;
import japicmp.model.JApiClass;
import japicmp.output.semver.SemverOut;
import japicmp.output.stdout.StdoutOutputGenerator;
import japicmp.output.xml.XmlOutput;
import japicmp.output.xml.XmlOutputGenerator;
import japicmp.output.xml.XmlOutputGeneratorOptions;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mojo(name = "cmp", requiresDependencyResolution = ResolutionScope.COMPILE, defaultPhase = LifecyclePhase.VERIFY)
public class JApiCmpMojo extends AbstractMojo {
	@org.apache.maven.plugins.annotations.Parameter(required = false)
	private Version oldVersion;
	@org.apache.maven.plugins.annotations.Parameter(required = false)
	private List<DependencyDescriptor> oldVersions;
	@org.apache.maven.plugins.annotations.Parameter(required = false)
	private Version newVersion;
	@org.apache.maven.plugins.annotations.Parameter(required = false)
	private List<DependencyDescriptor> newVersions;
	@org.apache.maven.plugins.annotations.Parameter(required = false)
	private Parameter parameter;
	@org.apache.maven.plugins.annotations.Parameter(required = false)
	private List<Dependency> dependencies;
	@org.apache.maven.plugins.annotations.Parameter(required = false)
	private List<Dependency> oldClassPathDependencies;
	@org.apache.maven.plugins.annotations.Parameter(required = false)
	private List<Dependency> newClassPathDependencies;
	@org.apache.maven.plugins.annotations.Parameter(required = false)
	private String skip;
	@org.apache.maven.plugins.annotations.Parameter(property = "project.build.directory", required = true)
	private File projectBuildDir;
	@Component
	private ArtifactFactory artifactFactory;
	@Component
	private ArtifactResolver artifactResolver;
	@org.apache.maven.plugins.annotations.Parameter(defaultValue = "${localRepository}")
	private ArtifactRepository localRepository;
	@org.apache.maven.plugins.annotations.Parameter(defaultValue = "${project.remoteArtifactRepositories}")
	private List<ArtifactRepository> artifactRepositories;
	@org.apache.maven.plugins.annotations.Parameter(defaultValue = "${project}")
	private MavenProject mavenProject;
	@org.apache.maven.plugins.annotations.Parameter(defaultValue = "${mojoExecution}", readonly = true)
	private MojoExecution mojoExecution;

	private static <T> T notNull(T value, String msg) throws MojoFailureException {
		if (value == null) {
			throw new MojoFailureException(msg);
		}
		return value;
	}

	public void execute() throws MojoExecutionException, MojoFailureException {
		MavenParameters mavenParameters = new MavenParameters(artifactRepositories, artifactFactory, localRepository, artifactResolver, mavenProject, mojoExecution);
			PluginParameters pluginParameters = new PluginParameters(skip, newVersion, oldVersion, parameter, dependencies, Optional.of(projectBuildDir), Optional.<String>absent(), true, oldVersions, newVersions, oldClassPathDependencies, newClassPathDependencies);
		executeWithParameters(pluginParameters, mavenParameters);
	}

	Optional<XmlOutput> executeWithParameters(PluginParameters pluginParameters, MavenParameters mavenParameters) throws MojoFailureException {
		if (Boolean.TRUE.toString().equalsIgnoreCase(pluginParameters.getSkipParam())) {
			getLog().info("Skipping execution because parameter 'skip' was set to true.");
			return Optional.absent();
		}
		if (filterModule(pluginParameters)) {
			return Optional.absent();
		}
		List<File> oldArchives = new ArrayList<>();
		List<File> newArchives = new ArrayList<>();
		populateArchivesListsFromParameters(pluginParameters, mavenParameters, oldArchives, newArchives);
		VersionChange versionChange = new VersionChange(oldArchives, newArchives);
		Options options = createOptions(pluginParameters.getParameterParam(), oldArchives, newArchives);
		List<JApiClass> jApiClasses = compareArchives(options, pluginParameters, mavenParameters);
		try {
			jApiClasses = applyPostAnalysisScript(parameter, jApiClasses);
			File jApiCmpBuildDir = createJapiCmpBaseDir(pluginParameters);
			String diffOutput = generateDiffOutput(jApiClasses, options);
			createFileAndWriteTo(diffOutput, jApiCmpBuildDir, mavenParameters);
			XmlOutput xmlOutput = generateXmlOutput(jApiClasses, jApiCmpBuildDir, options, mavenParameters, pluginParameters);
			if (pluginParameters.isWriteToFiles()) {
				List<File> filesWritten = XmlOutputGenerator.writeToFiles(options, xmlOutput);
				for (File file : filesWritten) {
					getLog().info("Written file '" + file.getAbsolutePath() + "'.");
				}
			}
			breakBuildIfNecessary(jApiClasses, pluginParameters.getParameterParam(), versionChange, options);
			return Optional.of(xmlOutput);
		} catch (IOException e) {
			throw new MojoFailureException(String.format("Failed to construct output directory: %s", e.getMessage()), e);
		}
	}

	private List<JApiClass> applyPostAnalysisScript(Parameter parameter, List<JApiClass> jApiClasses) throws MojoFailureException {
		List<JApiClass> filteredList = jApiClasses;
		if (parameter != null) {
			String postAnalysisFilterScript = parameter.getPostAnalysisScript();
			if (postAnalysisFilterScript != null) {
				if (Files.exists(Paths.get(postAnalysisFilterScript))) {
					ScriptEngine scriptEngine = new ScriptEngineManager().getEngineByName("groovy");
					Bindings bindings = scriptEngine.createBindings();
					bindings.put("jApiClasses", jApiClasses);
					try (InputStreamReader fileReader = new InputStreamReader(new FileInputStream(postAnalysisFilterScript), Charset.forName("UTF-8"))) {
						Object returnValue = scriptEngine.eval(fileReader, bindings);
						if (returnValue instanceof List) {
							List returnedList = (List) returnValue;
							filteredList = new ArrayList<>(returnedList.size());
							for (Object obj : returnedList) {
								if (obj instanceof JApiClass) {
									JApiClass jApiClass = (JApiClass) obj;
									filteredList.add(jApiClass);
								}
							}
						} else {
							throw new MojoFailureException("Post-analysis script does not return a list.");
						}
					} catch (ScriptException e) {
						throw new MojoFailureException("Execution of post-analysis script failed: " + e.getMessage(), e);
					} catch (FileNotFoundException e) {
						throw new MojoFailureException("Post-analysis script '" + postAnalysisFilterScript + " does not exist.");
					} catch (IOException e) {
						throw new MojoFailureException("Failed to load post-analysis script '" + postAnalysisFilterScript + ": " + e.getMessage(), e);
					}
				} else {
					throw new MojoFailureException("Post-analysis script '" + postAnalysisFilterScript + " does not exist.");
				}
			} else {
				getLog().debug("No post-analysis script provided.");
			}
		}
		return filteredList;
	}

	private boolean filterModule(PluginParameters pluginParameters) {
		if (mavenProject != null) {
			List<String> packagingSupporteds = pluginParameters.getParameterParam().getPackagingSupporteds();
			if ((packagingSupporteds != null) && (!packagingSupporteds.isEmpty())) {
				if (!packagingSupporteds.contains(mavenProject.getPackaging())) {
					getLog().info("Filtered according to packagingFilter");
					return true;
				}
			} else {
				getLog().debug("No packaging support defined, no filtering");
			}
			if ("pom".equals(mavenProject.getPackaging())) {
				boolean skipPomModules = true;
				Parameter parameterParam = pluginParameters.getParameterParam();
				if (parameterParam != null) {
					String skipPomModulesAsString = parameterParam.getSkipPomModules();
					if (skipPomModulesAsString != null) {
						skipPomModules = Boolean.valueOf(skipPomModulesAsString);
					}
				}
				if (skipPomModules) {
					getLog().info("Skipping execution because packaging of this module is 'pom'.");
					return true;
				}
			}
		}
		return false;
	}


	public static enum ConfigurationVersion {
		OLD, NEW
	}

	private void populateArchivesListsFromParameters(PluginParameters pluginParameters, MavenParameters mavenParameters, List<File> oldArchives, List<File> newArchives) throws MojoFailureException {
		if (pluginParameters.getOldVersionParam() != null) {
			oldArchives.addAll(retrieveFileFromConfiguration(pluginParameters.getOldVersionParam(), "oldVersion", mavenParameters, pluginParameters, ConfigurationVersion.OLD));
		}
		if (pluginParameters.getOldVersionsParam() != null) {
			for (DependencyDescriptor dependencyDescriptor : pluginParameters.getOldVersionsParam()) {
				if (dependencyDescriptor != null) {
					oldArchives.addAll(retrieveFileFromConfiguration(dependencyDescriptor, "oldVersions", mavenParameters, pluginParameters, ConfigurationVersion.OLD));
				}
			}
		}
		if (pluginParameters.getNewVersionParam() != null) {
			newArchives.addAll(retrieveFileFromConfiguration(pluginParameters.getNewVersionParam(), "newVersion", mavenParameters, pluginParameters, ConfigurationVersion.NEW));
		}
		if (pluginParameters.getNewVersionsParam() != null) {
			for (DependencyDescriptor dependencyDescriptor : pluginParameters.getNewVersionsParam()) {
				if (dependencyDescriptor != null) {
					newArchives.addAll(retrieveFileFromConfiguration(dependencyDescriptor, "newVersions", mavenParameters, pluginParameters, ConfigurationVersion.NEW));
				}
			}
		}
		if (oldArchives.size() == 0) {
			String message = "Please provide at least one resolvable old version using one of the configuration elements <oldVersion/> or <oldVersions/>.";
			if (ignoreNonResolvableArtifacts(pluginParameters) || ignoreMissingOldVersion(pluginParameters, ConfigurationVersion.OLD)) {
				getLog().warn(message);
			} else {
				throw new MojoFailureException(message);
			}
		}
		if (newArchives.size() == 0) {
			String message = "Please provide at least one resolvable new version using one of the configuration elements <newVersion/> or <newVersions/>.";
			if (ignoreNonResolvableArtifacts(pluginParameters)) {
				getLog().warn(message);
			} else {
				throw new MojoFailureException(message);
			}
		}
	}

	private void breakBuildIfNecessary(List<JApiClass> jApiClasses, Parameter parameterParam, VersionChange versionChange, Options options) throws MojoFailureException {
		if (breakBuildOnModificationsParameter(parameterParam)) {
			for (JApiClass jApiClass : jApiClasses) {
				if (jApiClass.getChangeStatus() != JApiChangeStatus.UNCHANGED) {
					throw new MojoFailureException(String.format("Breaking the build because there is at least one modified class: %s", jApiClass.getFullyQualifiedName()));
				}
			}
		}
		if (breakBuildOnBinaryIncompatibleModifications(parameterParam)) {
			for (JApiClass jApiClass : jApiClasses) {
				if (jApiClass.getChangeStatus() != JApiChangeStatus.UNCHANGED && !jApiClass.isBinaryCompatible()) {
					throw new MojoFailureException(String.format("Breaking the build because there is at least one binary incompatible class: %s", jApiClass.getFullyQualifiedName()));
				}
			}
		}
		if (breakBuildOnSourceIncompatibleModifications(parameterParam)) {
			for (JApiClass jApiClass : jApiClasses) {
				if (jApiClass.getChangeStatus() != JApiChangeStatus.UNCHANGED && !jApiClass.isSourceCompatible()) {
					throw new MojoFailureException(String.format("Breaking the build because there is at least one source incompatible class: %s", jApiClass.getFullyQualifiedName()));
				}
			}
		}
		if (breakBuildBasedOnSemanticVersioning(parameterParam)) {
			VersionChange.ChangeType changeType = versionChange.computeChangeType();
			SemverOut semverOut = new SemverOut(options, jApiClasses);
			String semver = semverOut.generate();
			if (changeType == VersionChange.ChangeType.MINOR && semver.equals("1.0.0")) {
				throw new MojoFailureException("Versions of archives indicate a minor change but binary incompatible changes found.");
			}
			if (changeType == VersionChange.ChangeType.PATCH && semver.equals("1.0.0")) {
				throw new MojoFailureException("Versions of archives indicate a patch change but binary incompatible changes found.");
			}
			if (changeType == VersionChange.ChangeType.PATCH && semver.equals("0.1.0")) {
				throw new MojoFailureException("Versions of archives indicate a patch change but binary compatible changes found.");
			}
			if (changeType == VersionChange.ChangeType.UNCHANGED && semver.equals("1.0.0")) {
				throw new MojoFailureException("Versions of archives indicate no API changes but binary incompatible changes found.");
			}
			if (changeType == VersionChange.ChangeType.UNCHANGED && semver.equals("0.1.0")) {
				throw new MojoFailureException("Versions of archives indicate no API changes but binary compatible changes found.");
			}
			if (changeType == VersionChange.ChangeType.UNCHANGED && semver.equals("0.0.1")) {
				throw new MojoFailureException("Versions of archives indicate no API changes but found API changes.");
			}
		}
	}

	private Options createOptions(Parameter parameterParam, List<File> oldVersions, List<File> newVersions) throws MojoFailureException {
		Options options = Options.newDefault();
		options.getOldArchives().addAll(oldVersions);
		options.getNewArchives().addAll(newVersions);
		if (parameterParam != null) {
			String accessModifierArg = parameterParam.getAccessModifier();
			if (accessModifierArg != null) {
				try {
					AccessModifier accessModifier = AccessModifier.valueOf(accessModifierArg.toUpperCase());
					options.setAccessModifier(accessModifier);
				} catch (IllegalArgumentException e) {
					throw new MojoFailureException(String.format("Invalid value for option accessModifier: %s. Possible values are: %s.", accessModifierArg, AccessModifier.listOfAccessModifier()));
				}
			}
			String onlyBinaryIncompatible = parameterParam.getOnlyBinaryIncompatible();
			if (onlyBinaryIncompatible != null) {
				Boolean booleanOnlyBinaryIncompatible = Boolean.valueOf(onlyBinaryIncompatible);
				options.setOutputOnlyBinaryIncompatibleModifications(booleanOnlyBinaryIncompatible);
			}
			String onlyModified = parameterParam.getOnlyModified();
			if (onlyModified != null) {
				Boolean booleanOnlyModified = Boolean.valueOf(onlyModified);
				options.setOutputOnlyModifications(booleanOnlyModified);
			}
			List<String> excludes = parameterParam.getExcludes();
			if (excludes != null) {
				for (String exclude : excludes) {
					options.addExcludeFromArgument(Optional.fromNullable(exclude));
				}
			}
			List<String> includes = parameterParam.getIncludes();
			if (includes != null) {
				for (String include : includes) {
					options.addIncludeFromArgument(Optional.fromNullable(include));
				}
			}
			String includeSyntheticString = parameterParam.getIncludeSynthetic();
			if (includeSyntheticString != null) {
				Boolean includeSynthetic = Boolean.valueOf(includeSyntheticString);
				options.setIncludeSynthetic(includeSynthetic);
			}
			String ignoreMissingClassesString = parameterParam.getIgnoreMissingClasses();
			if (ignoreMissingClassesString != null) {
				Boolean ignoreMissingClasses = Boolean.valueOf(ignoreMissingClassesString);
				options.setIgnoreMissingClasses(ignoreMissingClasses);
			}
			String htmlStylesheet = parameterParam.getHtmlStylesheet();
			if (htmlStylesheet != null) {
				options.setHtmlStylesheet(Optional.of(htmlStylesheet));
			}
			String noAnnotationsString = parameterParam.getNoAnnotations();
			if (noAnnotationsString != null) {
				Boolean noAnnotations = Boolean.valueOf(noAnnotationsString);
				options.setNoAnnotations(noAnnotations);
			}
		}
		return options;
	}

	private boolean breakBuildOnModificationsParameter(Parameter parameterParam) {
		boolean retVal = false;
		if (parameterParam != null) {
			retVal = Boolean.valueOf(parameterParam.getBreakBuildOnModifications());
		}
		return retVal;
	}

	private boolean breakBuildOnBinaryIncompatibleModifications(Parameter parameterParam) {
		boolean retVal = false;
		if (parameterParam != null) {
			retVal = Boolean.valueOf(parameterParam.getBreakBuildOnBinaryIncompatibleModifications());
		}
		return retVal;
	}

	private boolean breakBuildOnSourceIncompatibleModifications(Parameter parameter) {
		boolean retVal = false;
		if (parameter != null) {
			retVal = Boolean.valueOf(parameter.getBreakBuildOnSourceIncompatibleModifications());
		}
		return retVal;
	}

	private boolean breakBuildBasedOnSemanticVersioning(Parameter parameter) {
		boolean retVal = false;
		if (parameter != null) {
			retVal = Boolean.valueOf(parameter.getBreakBuildBasedOnSemanticVersioning());
		}
		return retVal;
	}

	private void createFileAndWriteTo(String diffOutput, File jApiCmpBuildDir, MavenParameters mavenParameters) throws IOException, MojoFailureException {
		File output = new File(jApiCmpBuildDir.getCanonicalPath() + File.separator + createFilename(mavenParameters) + ".diff");
		writeToFile(diffOutput, output);
	}

	private File createJapiCmpBaseDir(PluginParameters pluginParameters) throws MojoFailureException {
		if (pluginParameters.getProjectBuildDirParam().isPresent()) {
			try {
				File projectBuildDirParam = pluginParameters.getProjectBuildDirParam().get();
				if (projectBuildDirParam != null) {
					File jApiCmpBuildDir = new File(projectBuildDirParam.getCanonicalPath() + File.separator + "japicmp");
					boolean mkdirs = jApiCmpBuildDir.mkdirs();
					if (mkdirs || jApiCmpBuildDir.isDirectory() && jApiCmpBuildDir.canWrite()) {
						return jApiCmpBuildDir;
					}

					throw new MojoFailureException(String.format("Failed to create directory '%s'.", jApiCmpBuildDir.getAbsolutePath()));
				} else {
					throw new MojoFailureException("Maven parameter projectBuildDir is not set.");
				}
			} catch (IOException e) {
				throw new MojoFailureException("Failed to create output directory: " + e.getMessage(), e);
			}
		} else if (pluginParameters.getOutputDirectory().isPresent()) {
			String outputDirectory = pluginParameters.getOutputDirectory().get();
			if (outputDirectory != null) {
				File outputDirFile = new File(outputDirectory);
				boolean mkdirs = outputDirFile.mkdirs();
				if (mkdirs || outputDirFile.isDirectory() && outputDirFile.canWrite()) {
					return outputDirFile;
				}

				throw new MojoFailureException(String.format("Failed to create directory '%s'.", outputDirFile.getAbsolutePath()));
			} else {
				throw new MojoFailureException("Maven parameter outputDirectory is not set.");
			}
		} else {
			throw new MojoFailureException("None of the two parameters projectBuildDir and outputDirectory are present");
		}
	}

	private String generateDiffOutput(List<JApiClass> jApiClasses, Options options) {
		StdoutOutputGenerator stdoutOutputGenerator = new StdoutOutputGenerator(options, jApiClasses);
		return stdoutOutputGenerator.generate();
	}

	private XmlOutput generateXmlOutput(List<JApiClass> jApiClasses, File jApiCmpBuildDir, Options options, MavenParameters mavenParameters, PluginParameters pluginParameters) throws IOException, MojoFailureException {
		String filename = createFilename(mavenParameters);
		if (!skipXmlReport(pluginParameters)) {
			options.setXmlOutputFile(Optional.of(jApiCmpBuildDir.getCanonicalPath() + File.separator + filename + ".xml"));
		}
		if (!skipHtmlReport(pluginParameters)) {
			options.setHtmlOutputFile(Optional.of(jApiCmpBuildDir.getCanonicalPath() + File.separator + filename + ".html"));
		}
		SemverOut semverOut = new SemverOut(options, jApiClasses);
		XmlOutputGeneratorOptions xmlOutputGeneratorOptions = new XmlOutputGeneratorOptions();
		xmlOutputGeneratorOptions.setCreateSchemaFile(true);
		xmlOutputGeneratorOptions.setSemanticVersioningInformation(semverOut.generate());
		if (pluginParameters.getParameterParam() != null) {
			xmlOutputGeneratorOptions.setTitle(pluginParameters.getParameterParam().getHtmlTitle());
		}
		XmlOutputGenerator xmlGenerator = new XmlOutputGenerator(jApiClasses, options, xmlOutputGeneratorOptions);
		return xmlGenerator.generate();
	}

	private boolean skipHtmlReport(PluginParameters pluginParameters) {
		boolean skipReport = false;
		if (pluginParameters.getParameterParam() != null) {
			skipReport = Boolean.valueOf(pluginParameters.getParameterParam().getSkipHtmlReport());
		}
		return skipReport;
	}

	private boolean skipXmlReport(PluginParameters pluginParameters) {
		boolean skipReport = false;
		if (pluginParameters.getParameterParam() != null) {
			skipReport = Boolean.valueOf(pluginParameters.getParameterParam().getSkipXmlReport());
		}
		return skipReport;
	}

	private String createFilename(MavenParameters mavenParameters) {
		String filename = "japicmp";
		String executionId = mavenParameters.getMojoExecution().getExecutionId();
		if (executionId != null && !"default".equals(executionId)) {
			filename = executionId;
		}
		return filename;
	}

	private List<JApiClass> compareArchives(Options options, PluginParameters pluginParameters, MavenParameters mavenParameters) throws MojoFailureException {
		JarArchiveComparatorOptions comparatorOptions = JarArchiveComparatorOptions.of(options);
		setUpClassPath(comparatorOptions, pluginParameters, mavenParameters);
		JarArchiveComparator jarArchiveComparator = new JarArchiveComparator(comparatorOptions);
		return jarArchiveComparator.compare(options.getOldArchives(), options.getNewArchives());
	}

	private void setUpClassPath(JarArchiveComparatorOptions comparatorOptions, PluginParameters pluginParameters, MavenParameters mavenParameters) throws MojoFailureException {
		if (pluginParameters != null) {
			if (pluginParameters.getDependenciesParam() != null) {
				if (pluginParameters.getOldClassPathDependencies() != null || pluginParameters.getNewClassPathDependencies() != null) {
					throw new MojoFailureException("Please specify either a <dependencies/> element or the two elements <oldClassPathDependencies/> and <newClassPathDependencies/>. " +
						"With <dependencies/> you can specify one common classpath for both versions and with <oldClassPathDependencies/> and <newClassPathDependencies/> a " +
						"separate classpath for the new and old version.");
				} else {
					if (getLog().isDebugEnabled()) {
						getLog().debug("Element <dependencies/> found. Using " + JApiCli.ClassPathMode.ONE_COMMON_CLASSPATH);
					}
					for (Dependency dependency : pluginParameters.getDependenciesParam()) {
						List<File> files = resolveDependencyToFile("dependencies", dependency, mavenParameters, true, pluginParameters, ConfigurationVersion.NEW);
						for (File file : files) {
							comparatorOptions.getClassPathEntries().add(file.getAbsolutePath());
						}
						comparatorOptions.setClassPathMode(JarArchiveComparatorOptions.ClassPathMode.ONE_COMMON_CLASSPATH);
					}
				}
			} else {
				if (pluginParameters.getOldClassPathDependencies() != null || pluginParameters.getNewClassPathDependencies() != null) {
					if (getLog().isDebugEnabled()) {
						getLog().debug("At least one of the elements <oldClassPathDependencies/> or <newClassPathDependencies/> found. Using " + JApiCli.ClassPathMode.TWO_SEPARATE_CLASSPATHS);
					}
					if (pluginParameters.getOldClassPathDependencies() != null) {
						for (Dependency dependency : pluginParameters.getOldClassPathDependencies()) {
							List<File> files = resolveDependencyToFile("oldClassPathDependencies", dependency, mavenParameters, true, pluginParameters, ConfigurationVersion.OLD);
							for (File file : files) {
								comparatorOptions.getOldClassPath().add(file.getAbsolutePath());
							}
						}
					}
					if (pluginParameters.getNewClassPathDependencies() != null) {
						for (Dependency dependency : pluginParameters.getNewClassPathDependencies()) {
							List<File> files = resolveDependencyToFile("newClassPathDependencies", dependency, mavenParameters, true, pluginParameters, ConfigurationVersion.NEW);
							for (File file : files) {
								comparatorOptions.getNewClassPath().add(file.getAbsolutePath());
							}
						}
					}
					comparatorOptions.setClassPathMode(JarArchiveComparatorOptions.ClassPathMode.TWO_SEPARATE_CLASSPATHS);
				} else {
					if (getLog().isDebugEnabled()) {
						getLog().debug("None of the elements <oldClassPathDependencies/>, <newClassPathDependencies/> or <dependencies/> found. Using " + JApiCli.ClassPathMode.ONE_COMMON_CLASSPATH);
					}
					comparatorOptions.setClassPathMode(JarArchiveComparatorOptions.ClassPathMode.ONE_COMMON_CLASSPATH);
				}
			}
		}
		setUpClassPathUsingMavenProject(comparatorOptions, mavenParameters, pluginParameters, ConfigurationVersion.NEW);
	}

	private void setUpClassPathUsingMavenProject(JarArchiveComparatorOptions comparatorOptions, MavenParameters mavenParameters, PluginParameters pluginParameters, ConfigurationVersion configurationVersion) throws MojoFailureException {
		notNull(mavenParameters.getMavenProject(), "Maven parameter mavenProject should be provided by maven container.");
		Set<Artifact> dependencyArtifacts = mavenParameters.getMavenProject().getArtifacts();
		Set<String> classPathEntries = new HashSet<>();
		for (Artifact artifact : dependencyArtifacts) {
			String scope = artifact.getScope();
			if (!"test".equals(scope) && !artifact.isOptional()) {
				Set<Artifact> artifacts = resolveArtifact(artifact, mavenParameters, false, pluginParameters, configurationVersion);
				for (Artifact resolvedArtifact : artifacts) {
					File resolvedFile = resolvedArtifact.getFile();
					if (resolvedFile != null) {
						String absolutePath = resolvedFile.getAbsolutePath();
						if (!classPathEntries.contains(absolutePath)) {
							if (getLog().isDebugEnabled()) {
								getLog().debug("Adding to classpath: " + absolutePath + "; scope: " + scope);
							}
							classPathEntries.add(absolutePath);
						}
					}
				}
			}
		}
		for (String classPathEntry : classPathEntries) {
			comparatorOptions.getClassPathEntries().add(classPathEntry);
		}
	}

	private List<File> retrieveFileFromConfiguration(DependencyDescriptor dependencyDescriptor, String parameterName, MavenParameters mavenParameters, PluginParameters pluginParameters, ConfigurationVersion configurationVersion) throws MojoFailureException {
		List<File> files;
		if (dependencyDescriptor instanceof Dependency) {
			Dependency dependency = (Dependency) dependencyDescriptor;
			files = resolveDependencyToFile(parameterName, dependency, mavenParameters, false, pluginParameters, configurationVersion);
		} else if (dependencyDescriptor instanceof ConfigurationFile) {
			ConfigurationFile configurationFile = (ConfigurationFile) dependencyDescriptor;
			files = resolveConfigurationFileToFile(parameterName, configurationFile, configurationVersion, pluginParameters);
		} else {
			throw new MojoFailureException("DependencyDescriptor is not of type <dependency/> nor of type <configurationFile/>.");
		}
		return files;
	}

	private List<File> retrieveFileFromConfiguration(Version version, String parameterName, MavenParameters mavenParameters, PluginParameters pluginParameters, ConfigurationVersion configurationVersion) throws MojoFailureException {
		if (version != null) {
			Dependency dependency = version.getDependency();
			if (dependency != null) {
				return resolveDependencyToFile(parameterName, dependency, mavenParameters, false, pluginParameters, configurationVersion);
			} else if (version.getFile() != null) {
				ConfigurationFile configurationFile = version.getFile();
				return resolveConfigurationFileToFile(parameterName, configurationFile, configurationVersion, pluginParameters);
			} else {
				throw new MojoFailureException("Missing configuration parameter 'dependency'.");
			}
		}
		throw new MojoFailureException(String.format("Missing configuration parameter: %s", parameterName));
	}

	private List<File> resolveConfigurationFileToFile(String parameterName, ConfigurationFile configurationFile, ConfigurationVersion configurationVersion, PluginParameters pluginParameters) throws MojoFailureException {
		String path = configurationFile.getPath();
		if (path == null) {
			throw new MojoFailureException(String.format("The path element in the configuration of the plugin is missing for %s.", parameterName));
		}
		File file = new File(path);
		if (!file.exists()) {
			if (configurationVersion == ConfigurationVersion.OLD && ignoreMissingOldVersion(pluginParameters)) {
				getLog().warn("Could not find file, but ignoreMissingOldVersion is set tot true: " + file.getAbsolutePath());
			} else {
				throw new MojoFailureException(String.format("The path '%s' does not point to an existing file.", path));
			}
		}
		if (!file.isFile() || !file.canRead()) {
			if (configurationVersion == ConfigurationVersion.OLD && ignoreMissingOldVersion(pluginParameters)) {
				getLog().warn("The file given by path '" + file.getAbsolutePath() + "' is either not a file or is not readable, but ignoreMissingOldVersion is set tot true");
			} else {
				throw new MojoFailureException(String.format("The file given by path '%s' is either not a file or is not readable.", path));
			}
		}
		return Collections.singletonList(file);
	}

	private List<File> resolveDependencyToFile(String parameterName, Dependency dependency, MavenParameters mavenParameters, boolean transitively, PluginParameters pluginParameters, ConfigurationVersion configurationVersion) throws MojoFailureException {
		List<File> files = new ArrayList<>();
		if (getLog().isDebugEnabled()) {
			getLog().debug("Trying to resolve dependency '" + dependency + "' to file.");
		}
		if (dependency.getSystemPath() == null) {
			String descriptor = dependency.getGroupId() + ":" + dependency.getArtifactId() + ":" + dependency.getVersion();
			getLog().debug(parameterName + ": " + descriptor);
			Set<Artifact> artifacts = resolveArtifact(dependency, mavenParameters, transitively, pluginParameters, configurationVersion);
			for (Artifact artifact : artifacts) {
				File file = artifact.getFile();
				if (file != null) {
					files.add(file);
				} else {
					throw new MojoFailureException(String.format("Could not resolve dependency with descriptor '%s'.", descriptor));
				}
			}
			if (files.size() == 0) {
				String message = String.format("Could not resolve dependency with descriptor '%s'.", descriptor);
				if (ignoreNonResolvableArtifacts(pluginParameters) || ignoreMissingOldVersion(pluginParameters, configurationVersion)) {
					getLog().warn(message);
				} else {
					throw new MojoFailureException(message);
				}
			}
		} else {
			String systemPath = dependency.getSystemPath();
			Pattern pattern = Pattern.compile("\\$\\{([^\\}])");
			Matcher matcher = pattern.matcher(systemPath);
			if (matcher.matches()) {
				for (int i = 1; i <= matcher.groupCount(); i++) {
					String property = matcher.group(i);
					String propertyResolved = mavenParameters.getMavenProject().getProperties().getProperty(property);
					if (propertyResolved != null) {
						systemPath = systemPath.replaceAll("${" + property + "}", propertyResolved);
					} else {
						throw new MojoFailureException("Could not resolve property '" + property + "'.");
					}
				}
			}
			File file = new File(systemPath);
			boolean addFile = true;
			if (!file.exists()) {
				if (configurationVersion == ConfigurationVersion.OLD && ignoreMissingOldVersion(pluginParameters)) {
					getLog().warn("Could not find file, but ignoreMissingOldVersion is set tot true: " + file.getAbsolutePath());
				} else {
					throw new MojoFailureException("File '" + file.getAbsolutePath() + "' does not exist.");
				}
				addFile = false;
			}
			if (!file.canRead()) {
				if (configurationVersion == ConfigurationVersion.OLD && ignoreMissingOldVersion(pluginParameters)) {
					getLog().warn("File is not readable, but ignoreMissingOldVersion is set tot true: " + file.getAbsolutePath());
				} else {
					throw new MojoFailureException("File '" + file.getAbsolutePath() + "' is not readable.");
				}
				addFile = false;
			}
			if (addFile) {
				files.add(file);
			}
		}
		return files;
	}

	private boolean ignoreMissingOldVersion(PluginParameters pluginParameters) {
		boolean ignoreMissingOldVersion = false;
		if (pluginParameters.getParameterParam() != null) {
			ignoreMissingOldVersion = Boolean.valueOf(pluginParameters.getParameterParam().getIgnoreMissingOldVersion());
		}
		return ignoreMissingOldVersion;
	}

	private void writeToFile(String output, File outputfile) throws MojoFailureException, IOException {
		OutputStreamWriter fileWriter = null;
		try {
			fileWriter = new OutputStreamWriter(new FileOutputStream(outputfile), Charset.forName("UTF-8"));
			fileWriter.write(output);
			getLog().info("Written file '" + outputfile.getAbsolutePath() + "'.");
		} catch (Exception e) {
			throw new MojoFailureException(String.format("Failed to write diff file: %s", e.getMessage()), e);
		} finally {
			if (fileWriter != null) {
				fileWriter.close();
			}
		}
	}

	private Set<Artifact> resolveArtifact(Dependency dependency, MavenParameters mavenParameters, boolean transitively, PluginParameters pluginParameters, ConfigurationVersion configurationVersion) throws MojoFailureException {
		notNull(mavenParameters.getArtifactRepositories(), "Maven parameter artifactRepositories should be provided by maven container.");
		Artifact artifact = mavenParameters.getArtifactFactory().createArtifactWithClassifier(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion(), dependency.getType(), dependency.getClassifier());
		return resolveArtifact(artifact, mavenParameters, transitively, pluginParameters, configurationVersion);
	}

	private Set<Artifact> resolveArtifact(Artifact artifact, MavenParameters mavenParameters, boolean transitively, PluginParameters pluginParameters, ConfigurationVersion configurationVersion) throws MojoFailureException {
		notNull(mavenParameters.getLocalRepository(), "Maven parameter localRepository should be provided by maven container.");
		notNull(mavenParameters.getArtifactResolver(), "Maven parameter artifactResolver should be provided by maven container.");
		ArtifactResolutionRequest request = new ArtifactResolutionRequest();
		request.setArtifact(artifact);
		request.setLocalRepository(mavenParameters.getLocalRepository());
		request.setRemoteRepositories(mavenParameters.getArtifactRepositories());
		request.setResolutionFilter(new ArtifactFilter() {
			@Override
			public boolean include(Artifact artifact) {
				boolean include = true;
				if (artifact != null && artifact.isOptional()) {
					include = false;
				}
				return include;
			}
		});
		if (transitively) {
			request.setResolveTransitively(true);
		}
		ArtifactResolutionResult resolutionResult = mavenParameters.getArtifactResolver().resolve(request);
		if (resolutionResult.hasExceptions()) {
			List<Exception> exceptions = resolutionResult.getExceptions();
			String message = "Could not resolve " + artifact;
			if (ignoreNonResolvableArtifacts(pluginParameters) || ignoreMissingOldVersion(pluginParameters, configurationVersion)) {
				getLog().warn(message);
			} else {
				throw new MojoFailureException(message, exceptions.get(0));
			}
		}
		Set<Artifact> artifacts = resolutionResult.getArtifacts();
		if (artifacts.size() == 0) {
			String message = "Could not resolve " + artifact;
			if (ignoreNonResolvableArtifacts(pluginParameters) || ignoreMissingOldVersion(pluginParameters, configurationVersion)) {
				getLog().warn(message);
			} else {
				throw new MojoFailureException(message);
			}
		}
		return artifacts;
	}

	private boolean ignoreMissingOldVersion(PluginParameters pluginParameters, ConfigurationVersion configurationVersion) {
		return (configurationVersion == ConfigurationVersion.OLD && ignoreMissingOldVersion(pluginParameters));
	}

	private boolean ignoreNonResolvableArtifacts(PluginParameters pluginParameters) {
		boolean ignoreNonResolvableArtifacts = false;
		Parameter parameterParam = pluginParameters.getParameterParam();
		if (parameterParam != null) {
			String ignoreNonResolvableArtifactsAsString = parameterParam.getIgnoreNonResolvableArtifacts();
			if (Boolean.TRUE.toString().equalsIgnoreCase(ignoreNonResolvableArtifactsAsString)) {
				ignoreNonResolvableArtifacts = true;
			}
		}
		return ignoreNonResolvableArtifacts;
	}
}
