package org.wocommunity.maven.plugins.woinstall;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.eclipse.aether.DefaultRepositoryCache;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.installation.InstallRequest;
import org.eclipse.aether.installation.InstallationException;
import org.eclipse.aether.repository.LocalArtifactRequest;
import org.eclipse.aether.repository.LocalArtifactResult;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.LocalRepositoryManager;
import org.eclipse.aether.util.artifact.SubArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wocommunity.maven.plugins.woinstall.io.InstallerDownloadUtil;

@Mojo(name = "woinstall", requiresProject = false, defaultPhase = LifecyclePhase.INITIALIZE, aggregator = true)
public class InstallMojo extends AbstractMojo {
	private static final Logger LOG = LoggerFactory.getLogger(InstallMojo.class);

	private static final String WEBOBJECTS_GROUP_ID = "com.webobjects";

	private static final String WEBOBJECTS_BOM_ARTIFACT_ID = "webobjects-bom";

	@Component
	private RepositorySystem repositorySystem;

	@Parameter(defaultValue = "${session}", required = true, readonly = true)
	private MavenSession session;

	/**
	 * The path for a specific local repository directory. If not specified the
	 * local repository path configured in the Maven settings will be used.
	 */
	@Parameter(property = "localRepositoryPath")
	private File localRepositoryPath;

	/**
	 * The version of WebObjects to download and install.
	 */
	@Parameter(defaultValue = "5.4.3", property = "installVersion", required = true)
	private String installVersion;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		/*
		 * Locate the WebObjectsInstaller. Throw an error if one does not exist for the
		 * requested version and exit.
		 */
		WebObjectsInstaller installer;
		try {
			installer = WebObjectsInstaller.forVersion(installVersion);
		} catch (final UnknownVersionException e) {
			throw new MojoFailureException("Invalid WebObjects version.", e);
		}
		LOG.info("WebObjects installation starting for version {}", installer.getVersion());

		/*
		 * Find the location where the WebObjects installation will be found.
		 */
		final RepositorySystemSession repositorySystemSession = getRepositorySystemSession();
		final File localRepo = repositorySystemSession
				.getLocalRepositoryManager()
				.getRepository()
				.getBasedir();
		LOG.info("Preparing to install WebObjects version {} to repository: {}", installer.getVersion(), localRepo);

		/*
		 * Download or resume webobjects installer if necessary.
		 */
		try {
			InstallerDownloadUtil.downloadInstallerToRepo(installer, localRepo);
		} catch (final IOException e) {
			throw new MojoFailureException("Installer download failed.", e);
		}
		LOG.info("Installer downloaded successfully.");

		// unpack dmg into a .next_root
		try {
			installer.installNextRoot(localRepo);
		} catch (final IOException e) {
			throw new MojoFailureException("Installing next_root failed.", e);
		}
		LOG.info("Next root installation complete.");

		// copy .next_root resources into maven repository
		try {
			installArtifacts(installer, repositorySystemSession);
		} catch (final IOException e) {
			throw new MojoFailureException("Installing artifacts failed.", e);
		}
		LOG.info("Artifact installation complete.");

		// TODO create a webobjects bom project

		// print location of .next_root and dmg
		LOG.info("WebObjects installation complete");
		LOG.info(installer.getLicenseText());
		LOG.info("WebObjects installer archive located at {}", installer.getInstallerFile(localRepo).getPath());
		LOG.info("WebObjects NEXT_ROOT directory located at {}", installer.getNextRoot(localRepo).getPath());
	}

	private RepositorySystemSession getRepositorySystemSession() {
		RepositorySystemSession repositorySystemSession = session.getRepositorySession();
		if (localRepositoryPath != null) {
			// "clone" repository session and replace localRepository
			final DefaultRepositorySystemSession newSession = new DefaultRepositorySystemSession(
					session.getRepositorySession());
			// Clear cache, since we're using a new local repository
			newSession.setCache(new DefaultRepositoryCache());
			// keep same repositoryType
			String contentType = newSession.getLocalRepository().getContentType();
			if ("enhanced".equals(contentType)) {
				contentType = "default";
			}
			final LocalRepositoryManager localRepositoryManager = repositorySystem.newLocalRepositoryManager(
					newSession, new LocalRepository(localRepositoryPath, contentType));
			newSession.setLocalRepositoryManager(localRepositoryManager);
			repositorySystemSession = newSession;
			LOG.debug("localRepoPath: {}", localRepositoryManager.getRepository().getBasedir());
		}
		return repositorySystemSession;
	}

	private void installArtifacts(
			final WebObjectsInstaller installer,
			final RepositorySystemSession rss) throws IOException {
		final File localRepo = rss
				.getLocalRepositoryManager()
				.getRepository()
				.getBasedir();
		final String version = installer.getVersion();
		final Map<String, List<String>> dependencyMap = installer.dependencyMap();
		final File jarDir = installer.getJarRoot(localRepo);
		final List<File> jars = Arrays.asList(jarDir.listFiles()).stream()
				.filter(File::isFile)
				.filter(f -> f.getName().endsWith(".jar"))
				.collect(Collectors.toList());
		final List<String> bomArtifactIds = new ArrayList<>(jars.size());
		for (final File jar : jars) {
			final String artifactId = jar.getName().substring(0, jar.getName().length() - 4);
			bomArtifactIds.add(artifactId);
			final LocalArtifactRequest req = new LocalArtifactRequest()
					.setArtifact(new DefaultArtifact(WEBOBJECTS_GROUP_ID, artifactId, "jar", version));
			final LocalArtifactResult res = rss.getLocalRepositoryManager().find(rss, req);
			if (res.isAvailable()) {
				// Already installed, skip.
				continue;
			}
			final List<String> dependencies = dependencyMap.getOrDefault(artifactId, Collections.emptyList());
			final File pom = generatePomForArtifact(artifactId, version, dependencies);
			try {
				installWoArtifact(rss, artifactId, version, jar, pom);
			} catch (final InstallationException e) {
				LOG.error("Error installing artifactId: " + artifactId, e);
				throw new IOException(e);
			} finally {
				pom.delete();
			}
		}
		// Install webobjects-bom if it doesn't exist
		final LocalArtifactRequest req = new LocalArtifactRequest()
				.setArtifact(new DefaultArtifact(WEBOBJECTS_GROUP_ID, WEBOBJECTS_BOM_ARTIFACT_ID, "pom", version));
		final LocalArtifactResult res = rss.getLocalRepositoryManager().find(rss, req);
		if (!res.isAvailable()) {
			final File bom = generatePomForBom(version, bomArtifactIds);
			final InstallRequest ireq = new InstallRequest();
			final Artifact bomArtifact = new DefaultArtifact(WEBOBJECTS_GROUP_ID, WEBOBJECTS_BOM_ARTIFACT_ID, "pom",
					version)
					.setFile(bom);
			ireq.addArtifact(bomArtifact);
			try {
				repositorySystem.install(rss, ireq);
			} catch (final InstallationException e) {
				LOG.error("Error installing webobjects-bom", e);
				throw new IOException(e);
			} finally {
				bom.delete();
			}
		}
	}

	private File generatePomForBom(
			final String version,
			final List<String> artifactIds)
			throws IOException {

		final Model model = new Model();
		model.setModelVersion("4.0.0");
		model.setGroupId(WEBOBJECTS_GROUP_ID);
		model.setArtifactId(WEBOBJECTS_BOM_ARTIFACT_ID);
		model.setDescription("WebObjects Bill of Materials");
		model.setVersion(version);
		Collections.sort(artifactIds);
		final DependencyManagement mgt = new DependencyManagement();
		for (final String artifactId : artifactIds) {
			final Dependency dep = new Dependency();
			dep.setGroupId(WEBOBJECTS_GROUP_ID);
			dep.setArtifactId(artifactId);
			dep.setVersion(version);
			mgt.addDependency(dep);
		}
		model.setDependencyManagement(mgt);

		final Path tempPomFile = Files.createTempFile(WEBOBJECTS_BOM_ARTIFACT_ID, ".pom");
		try (OutputStream writer = Files.newOutputStream(tempPomFile)) {
			new MavenXpp3Writer().write(writer, model);
			return tempPomFile.toFile();
		}
	}

	private File generatePomForArtifact(
			final String artifactId,
			final String version,
			final List<String> dependencies) throws IOException {
		final Model model = generateModelForArtifact(artifactId, version, dependencies);
		final Path tempPomFile = Files.createTempFile(artifactId, ".pom");
		try (OutputStream writer = Files.newOutputStream(tempPomFile)) {
			new MavenXpp3Writer().write(writer, model);
			return tempPomFile.toFile();
		}
	}

	private Model generateModelForArtifact(
			final String artifactId,
			final String version,
			final List<String> dependencies) {
		final Model model = new Model();
		model.setModelVersion("4.0.0");
		model.setGroupId(WEBOBJECTS_GROUP_ID);
		model.setArtifactId(artifactId);
		model.setVersion(version);
		if (!dependencies.isEmpty()) {
			final List<Dependency> deps = dependencies.stream().map(name -> {
				final Dependency dep = new Dependency();
				dep.setGroupId(WEBOBJECTS_GROUP_ID);
				dep.setArtifactId(name);
				dep.setVersion(version);
				return dep;
			}).collect(Collectors.toList());
			model.setDependencies(deps);
		}
		return model;
	}

	private void installWoArtifact(final RepositorySystemSession rss,
			final String artifactId,
			final String version,
			final File jar,
			final File pom)
			throws InstallationException {
		final InstallRequest ireq = new InstallRequest();
		final Artifact jarArtifact = new DefaultArtifact(WEBOBJECTS_GROUP_ID, artifactId, "jar", version)
				.setFile(jar);
		ireq.addArtifact(jarArtifact);
		final Artifact pomArtifact = new SubArtifact(jarArtifact, "", "pom", pom);
		ireq.addArtifact(pomArtifact);
		repositorySystem.install(rss, ireq);
	}
}
