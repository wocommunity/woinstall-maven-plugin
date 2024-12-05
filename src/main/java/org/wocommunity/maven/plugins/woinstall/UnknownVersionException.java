package org.wocommunity.maven.plugins.woinstall;

import java.util.Arrays;

public class UnknownVersionException extends Exception {

	private static final long serialVersionUID = 1L;

	public UnknownVersionException(final String version) {
		super(message(version));
	}

	private static String message(final String version) {
		return "Unknown version " + version + ". Available versions are "
				+ Arrays.asList(WebObjectsInstaller.values()).toString();
	}
}
