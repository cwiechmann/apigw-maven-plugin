package com.axway.maven.apigw;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import com.axway.maven.apigw.utils.FedBuilder;
import com.axway.maven.apigw.utils.ProjectDeploy;
import com.axway.maven.apigw.utils.ProjectPack;
import com.axway.maven.apigw.utils.Source;
import com.axway.maven.apigw.utils.Target;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Mojo(name = "deploy", defaultPhase = LifecyclePhase.NONE, requiresProject = true, threadSafe = false)
@Execute(phase = LifecyclePhase.PACKAGE)
public class DeploymentMojo extends AbstractGatewayMojo {

	public static final String DEPLOY_DIR_NAME = "axway-deploy";

	private static final String PROJECT_NAME = "gateway";

	@Parameter(property = "axway.anm.host", required = true)
	private String anmHost;

	@Parameter(property = "axway.anm.port", required = true, defaultValue = "8090")
	private int anmPort;

	@Parameter(property = "axway.anm.user", required = true, defaultValue = "admin")
	private String anmUser;

	@Parameter(property = "axway.anm.password", required = true)
	private String anmPassword;

	@Parameter(property = "axway.deploy.group", required = true)
	private String deployGroup;

	@Parameter(property = "axway.passphrase.deploy", required = false)
	private String passphraseDeploy = null;

	public DeploymentMojo() {
	}

	private File getTempDir() {
		return new File(getTargetDir(), DEPLOY_DIR_NAME);
	}

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		PackageType pkg = getPackageType();

		switch (pkg) {
		case POLICY:
			deployPolicyProject();
			break;

		case SERVER:
			deployServerProject();
			break;

		case DEPLOYMENT:
			deployDeploymentProject();
			break;

		default:
			throw new MojoExecutionException("Unsupported package type: " + getPackageType());
		}
	}

	private void deployPolicyProject() throws MojoExecutionException {
		try {
			// pack test server project
			ProjectPack packer = new ProjectPack(this.homeAxwayGW, getLog());
			packer.setPassphrasePol(this.passphrasePol);
			int exitCode = packer.execute(getTempDir(), PROJECT_NAME, this.testServerDirectory, buildPolicyProperties());
			if (exitCode != 0) {
				throw new MojoExecutionException("failed to build packed project");
			}

			// configure fed
			File pol = new File(getTempDir(), PROJECT_NAME + ".pol");
			File env = new File(getTempDir(), PROJECT_NAME + ".env");
			File info = new File(getTempDir(), PROJECT_NAME + ".info.json");

			ObjectMapper mapper = new ObjectMapper();
			ObjectNode root = buildBasicArtifactInfo();
			String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
			FileUtils.writeStringToFile(info, json, "UTF-8");

			File fed = configFed(pol, env, info);

			// deploy to server
			deployFed(fed);
		} catch (IOException e) {
			throw new MojoExecutionException("Error on packing project", e);
		}
	}

	private void deployServerProject() throws MojoExecutionException {
		File archiveBuildDir = getArchiveBuildDir();

		// configure fed
		File pol = new File(archiveBuildDir, ServerArchiveMojo.FILE_GATEWAY_POL);
		File env = new File(archiveBuildDir, ServerArchiveMojo.FILE_GATEWAY_ENV);
		File info = new File(archiveBuildDir, ServerArchiveMojo.FILE_GATEWAY_INFO);

		File fed = configFed(pol, env, info);

		// deploy to server
		deployFed(fed);
	}

	private void deployDeploymentProject() throws MojoExecutionException {
		File fed = new File(getArchiveBuildDir(), DeploymentArchiveMojo.FILE_FED_NAME);

		deployFed(fed);
	}

	private File configFed(File pol, File env, File info) throws MojoExecutionException {
		FedBuilder fb = new FedBuilder(this, pol, env, this.configConfigFile, info);
		fb.setPassphrasePol(this.passphrasePol);
		fb.setPassphraseFed(this.passphraseFed);

		fb.addPropertyFiles(getPropertyFiles());

		fb.enableVerboseMode(this.verboseCfgTools);
		
		if (this.configCertsFile != null) {
			fb.setCertificatesFile(this.configCertsFile);
			fb.setCertificateExpirationDays(this.certExpirationDays);
			fb.enableCertificateConfigFileUpdate(this.updateCertConfigFile);
			fb.setCertificatesBasePath(this.configCertsBaseDir);
		}
		if (this.configSecretsFile != null) {
			if (this.configSecretsKey == null)
				throw new MojoExecutionException("Key file for secrets is not specified!");
			fb.setSecrets(this.configSecretsFile, this.configSecretsKey);
		}

		File fed = new File(getTempDir(), PROJECT_NAME + ".fed");

		int exitCode = fb.execute(fed, null);
		if (exitCode != 0) {
			throw new MojoExecutionException("failed to configure project: exitCode=" + exitCode);
		}

		return fed;
	}

	private void deployFed(File fed) throws MojoExecutionException {
		try {
			Map<String, String> polProps = buildPolicyProperties();

			Source source = new Source(fed, this.passphraseFed);
			Target target = new Target(this.deployGroup, this.passphraseDeploy);

			// Deploying to a Classic Gateway, ok to use projdeploy
			this.getLog().info("Using projdeploy to deploy the fed file");
			ProjectDeploy deploy = new ProjectDeploy(this.homeAxwayGW, getDomain(), getLog());
			int exitCode = deploy.execute(source, target, polProps, null);
			if (exitCode != 0) {
				throw new MojoExecutionException("Failed to deploy project: exitCode=" + exitCode);
			}
		} catch (IOException e) {
			throw new MojoExecutionException("Error on deploying project", e);
		}
	}

	private ProjectDeploy.Domain getDomain() {
		return new ProjectDeploy.Domain(this.anmHost, this.anmPort, this.anmUser, this.anmPassword);
	}
}
