/**
 *
 */
package org.wocommunity.maven.plugins.woinstall;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.testing.AbstractMojoTestCase;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingRequest;
import org.codehaus.plexus.util.FileUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.internal.impl.DefaultLocalPathComposer;
import org.eclipse.aether.internal.impl.DefaultLocalPathPrefixComposerFactory;
import org.eclipse.aether.internal.impl.DefaultTrackingFileManager;
import org.eclipse.aether.internal.impl.EnhancedLocalRepositoryManagerFactory;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.NoLocalRepositoryManagerException;

/**
 *
 */
public class InstallMojoTest extends AbstractMojoTestCase {
	private final String LOCAL_REPO = "target/local-repo/";
	private final String SPECIFIC_LOCAL_REPO = "target/specific-local-repo/";

	private String installVersion;
	private String localRepositoryPath;

	@Override
	public void setUp() throws Exception {
		super.setUp();

		FileUtils.deleteDirectory(new File(getBasedir() + "/" + LOCAL_REPO));
		FileUtils.deleteDirectory(new File(getBasedir() + "/" + SPECIFIC_LOCAL_REPO));
	}

	private MavenSession createMavenSession(final String localRepositoryBaseDir)
			throws NoLocalRepositoryManagerException {
		final MavenSession session = mock(MavenSession.class);
		final DefaultRepositorySystemSession repositorySession = new DefaultRepositorySystemSession();
		repositorySession.setLocalRepositoryManager(new EnhancedLocalRepositoryManagerFactory(
				new DefaultLocalPathComposer(),
				new DefaultTrackingFileManager(),
				new DefaultLocalPathPrefixComposerFactory())
				.newInstance(repositorySession, new LocalRepository(localRepositoryBaseDir)));
		final ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest();
		buildingRequest.setRepositorySession(repositorySession);
		when(session.getProjectBuildingRequest()).thenReturn(buildingRequest);
		when(session.getRepositorySession()).thenReturn(repositorySession);
		return session;
	}

	private void assignValuesForParameter(final Object obj) throws Exception {
		installVersion = (String) getVariableValueFromObject(obj, "installVersion");
		localRepositoryPath = (String) getVariableValueFromObject(obj, "localRepositoryPath");
	}

	public void testInstallFile() throws Exception {
		final File testPom = new File(getBasedir(), "target/test-classes/project-to-test/pom.xml");
		final InstallMojo mojo = (InstallMojo) lookupMojo("woinstall", testPom);
		assertNotNull(mojo);

		setVariableValueToObject(mojo, "session", createMavenSession(LOCAL_REPO));
		assignValuesForParameter(mojo);

		/*
		 * Uncomment this line if you want to actually run the plugin. Commented out
		 * because otherwise, you'll be downloading 140MB of .dmg every time you run the
		 * build which is not fast.
		 */
		// mojo.execute();

		/*
		 * TODO check that files exist proving the test worked! I pinky swear I did this
		 * manually however ;)
		 */
	}

}
