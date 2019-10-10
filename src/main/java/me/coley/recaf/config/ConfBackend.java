package me.coley.recaf.config;

import java.io.File;
import java.util.*;

/**
 * Private configuration that are intended for user-access.
 *
 * @author Matt
 */
public class ConfBackend extends Config {
	private static final String CURRENT_DIR = System.getProperty("user.dir");
	/**
	 * Recently opened files <i>(from primary resources)</i> - absolute paths.
	 */
	@Conf("backend.recents")
	public List<String> recentFiles = new ArrayList<>();
	/**
	 * Number of recent files allowed to be stored in {@link #recentFiles}.
	 */
	@Conf("backend.maxrecent")
	public int maxRecentFiles = 6;
	/**
	 * Recent path used by the load dialog.
	 */
	@Conf("backend.recentopen")
	public String recentLoad = CURRENT_DIR;
	/**
	 * Recent path used by the save-application dialog.
	 */
	@Conf("backend.recentsave")
	public String recentSaveApp = CURRENT_DIR;
	/**
	 * Recent path used by the save-workspace dialog.
	 */
	@Conf("backend.recentsave")
	public String recentSaveWorkspace = CURRENT_DIR;

	ConfBackend() {
		super("misc-backend");
	}

	/**
	 * @return Copy of recently opened files. {@link #recentFiles}.
	 */
	public List<String> getRecentFiles() {
		return new ArrayList<>(recentFiles);
	}

	/**
	 * @return Directory to use for import file-chooser.
	 * Based on the most recent file opened.
	 */
	public File getRecentLoadDir() {
		return getDir(recentLoad);
	}

	/**
	 * @return Directory to use for save-application file-chooser.
	 * Based on the most recent file exported.
	 */
	public File getRecentSaveAppDir() {
		return getDir(recentSaveApp);
	}

	/**
	 * @return Directory to use for save-workspace file-chooser.
	 * Based on the most recent file exported.
	 */
	public File getRecentSaveWorkspaceDir() {
		return getDir(recentSaveWorkspace);
	}

	private File getDir(String root) {
		File recent = new File(root);
		if(recent.exists()) {
			if(recent.isDirectory())
				return recent;
			File parent = recent.getParentFile();
			if(parent != null)
				return parent;
		}
		return new File(CURRENT_DIR);
	}
}