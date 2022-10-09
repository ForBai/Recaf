package me.coley.recaf.ui.pane;

import com.sun.tools.attach.*;
import com.sun.tools.attach.spi.AttachProvider;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import me.coley.recaf.RecafUI;
import me.coley.recaf.ui.control.ActionButton;
import me.coley.recaf.util.Directories;
import me.coley.recaf.util.StringUtil;
import me.coley.recaf.util.logging.Logging;
import me.coley.recaf.util.threading.FxThreadUtil;
import me.coley.recaf.util.threading.ThreadPoolFactory;
import me.coley.recaf.util.threading.ThreadUtil;
import me.coley.recaf.workspace.Workspace;
import me.coley.recaf.workspace.resource.AgentResource;
import me.coley.recaf.workspace.resource.Resources;
import org.slf4j.Logger;
import software.coley.instrument.BuildConfig;
import software.coley.instrument.Client;
import software.coley.instrument.Extractor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

/**
 * Displays various {@link VirtualMachine} instances that can be attached to.
 *
 * @author Matt Coley
 */
public class AttachPane extends BorderPane {
	private static final Logger logger = Logging.get(AttachPane.class);
	private static final long currentPid = ProcessHandle.current().pid();
	private static final AttachPane instance = new AttachPane();
	private final DescriptorComparator descriptorComparator = new DescriptorComparator();
	private final Map<VirtualMachineDescriptor, VirtualMachine> virtualMachineMap = new HashMap<>();
	private final Map<VirtualMachineDescriptor, Exception> virtualMachineFailureMap = new HashMap<>();
	private final Map<VirtualMachineDescriptor, Integer> virtualMachinePidMap = new HashMap<>();
	private final Map<VirtualMachineDescriptor, Properties> virtualMachinePropertiesMap = new HashMap<>();
	private final Map<VirtualMachineDescriptor, String> virtualMachineMainClassMap = new HashMap<>();
	private final ObservableList<VirtualMachineDescriptor> virtualMachineDescriptors = FXCollections.observableArrayList();
	private boolean agentExtractFailure;

	private AttachPane() {
		extractAgent();
		// Schedule checking for new VMs
		ThreadUtil.scheduleAtFixedRate(() -> FxThreadUtil.run(this::update), 0, 1, TimeUnit.SECONDS);
		// Setup UI
		BorderPane listWrapper = new BorderPane();
		listWrapper.setPadding(new Insets(5));
		ListView<VirtualMachineDescriptor> machineListView = new ListView<>(virtualMachineDescriptors);
		machineListView.setCellFactory(param -> new VMCell());
		listWrapper.setCenter(machineListView);
		setCenter(listWrapper);
		// Disable UI if the agent jar could not be extracted
		if (agentExtractFailure)
			setDisable(true);
	}

	public static AttachPane getInstance() {
		return instance;
	}

	/**
	 * Ensure the agent jar exists.
	 */
	private void extractAgent() {
		Path agentPath = getAgentJarPath();
		if (!Files.isRegularFile(agentPath)) {
			try {
				logger.debug("Extracting agent jar to Recaf directory: {}", agentPath.getFileName());
				Files.createDirectories(Directories.getAgentDirectory());
				Extractor.extractToPath(agentPath);
			} catch (IOException e) {
				agentExtractFailure = true;
			}
		}
	}

	/**
	 * @return Path to agent jar file.
	 */
	private Path getAgentJarPath() {
		String jarName = "agent-" + BuildConfig.VERSION + ".jar";
		return Directories.getAgentDirectory().resolve(jarName);
	}

	/**
	 * Check for new virtual machines.
	 * <br>
	 * <b>Must be invoked on the FX thread.</b>
	 */
	private void update() {
		List<VirtualMachineDescriptor> list = VirtualMachine.list();
		Set<VirtualMachineDescriptor> toRemove = new HashSet<>(virtualMachineDescriptors);
		for (VirtualMachineDescriptor descriptor : list) {
			// Still active in VM list, keep it.
			toRemove.remove(descriptor);
			// Add if not in the list.
			if (!virtualMachineDescriptors.contains(descriptor)) {
				String label = descriptor.id() + " - " + StringUtil.withEmptyFallback(descriptor.displayName(), "?");
				try {
					int pid = mapToPid(descriptor);
					if (pid == currentPid) // skip self
						continue;
					AttachProvider provider = descriptor.provider();
					VirtualMachine machine = provider.attachVirtualMachine(descriptor);
					virtualMachineMap.put(descriptor, machine);
					logger.trace("Remote JVM descriptor found (attach-success): " + label);
					// Extract additional information
					virtualMachinePropertiesMap.put(descriptor, machine.getSystemProperties());
					virtualMachinePidMap.put(descriptor, pid);
					virtualMachineMainClassMap.put(descriptor, mapToMainClass(descriptor));
				} catch (IOException ex) {
					virtualMachineFailureMap.put(descriptor, ex);
					logger.trace("Remote JVM descriptor found (attach-success, read-failure): " + label);
				} catch (AttachNotSupportedException ex) {
					virtualMachineFailureMap.put(descriptor, ex);
					logger.trace("Remote JVM descriptor found (attach-failure): " + label);
				} catch (Throwable t) {
					logger.error("Unhandled exception populating remote VM info", t);
				}
				// Insert descriptor in sorted order.
				int lastComparison = 1;
				for (int i = 0; i < virtualMachineDescriptors.size(); i++) {
					VirtualMachineDescriptor other = virtualMachineDescriptors.get(i);
					int comparison = descriptorComparator.compare(descriptor, other);
					if (comparison < lastComparison) {
						virtualMachineDescriptors.add(i, descriptor);
						return;
					}
				}
				// Greater than all entries, append to end
				virtualMachineDescriptors.add(descriptor);
			}
		}
		// Remove entries not visited in this pass
		virtualMachineDescriptors.removeAll(toRemove);
	}

	/**
	 * @param descriptor
	 * 		VM descriptor.
	 *
	 * @return Main class of VM, if possible to resolve.
	 */
	private String mapToMainClass(VirtualMachineDescriptor descriptor) {
		// Get source string to find main class name from
		String source = descriptor.displayName();
		if (source == null || source.isBlank() || source.toLowerCase().contains(".jar")) {
			Properties properties = virtualMachinePropertiesMap.get(descriptor);
			if (properties != null) {
				// Check if we can get the main class from the command.
				// It may start with it, such as "com.example.Main args..."
				// it may be a file path, such as from "-jar <path> <args...>"
				String command = properties.getProperty("sun.java.command", "");
				// Check if the command is a path.
				String commandLower = command.toLowerCase();
				int jarIndex = commandLower.indexOf(".jar");
				if (jarIndex > 0) {
					// Depending on the invocation, it may not be the full path.
					// We may need to pre-pend the current directory
					String commandJarName = command.substring(0, jarIndex + 4);
					try {
						Path jarPath = Paths.get(commandJarName);
						if (!Files.isRegularFile(jarPath)) {
							// Prepend remote vm's user directory
							String commandUserDir = properties.getProperty("user.dir");
							if (commandUserDir.endsWith("/"))
								commandUserDir = commandUserDir.substring(0, commandUserDir.length() - 1);
							if (commandJarName.startsWith("/"))
								commandJarName = commandJarName.substring(1);
							jarPath = Paths.get(commandUserDir + "/" + commandJarName);
						}
						// Read main class attribute from jar manifest
						if (Files.isRegularFile(jarPath)) {
							try (JarFile jar = new JarFile(jarPath.toFile())) {
								source = jar.getManifest().getMainAttributes().getValue("Main-Class");
							} catch (IOException ignored) {
								// Can't read from jar, oh well
							}
						}
					} catch (InvalidPathException ignored) {
						// Expected for cases like 'com.example.Main foo.jar'
						// In this case we know the substring up to the '.jar' isn't a path in totality.
						// Only a section of it is, so likely the '.jar' match is part of an argument.
					}
				}
			}
		}
		// Still null/missing? Give up
		if (source == null || source.isEmpty())
			return "<unknown main-class>";
		// Some 'display name' values are '<class> <args...>' so strip out the args
		String trim = source.trim();
		int end = trim.indexOf(' ');
		if (end == -1)
			end = trim.length();
		return trim.substring(0, end);
	}

	/**
	 * @param descriptor
	 * 		VM descriptor.
	 *
	 * @return PID of VM process.
	 */
	private int mapToPid(VirtualMachineDescriptor descriptor) {
		String id = descriptor.id();
		if (id.matches("\\d+")) {
			return Integer.parseInt(descriptor.id());
		} else {
			return -1;
		}
	}

	/**
	 * Connect to the given VM.
	 *
	 * @param item
	 * 		VM descriptor for VM to connect to.
	 */
	private void connect(VirtualMachineDescriptor item) {
		VirtualMachine virtualMachine = virtualMachineMap.get(item);
		try {
			// Will initialize agent server with default arguments
			try {
				String agentAbsolutePath = getAgentJarPath().toAbsolutePath().toString();
				virtualMachine.loadAgent(agentAbsolutePath);
			} catch (AgentLoadException ex) {
				// The agent jar file is written in Java 8. But Recaf uses Java 11+.
				// This is a problem on OUR side because Java 11+ handles agent interactions differently.
				//  - https://stackoverflow.com/a/54454418/
				// Basically in Java 10 they added a prefix string requirement.
				// But the Java 8 VM doesn't have that so our VM will mark it as invalid.
				if (!ex.getMessage().equals("0"))
					// If the result we get back is '0' then that means it was actually a success
					throw ex;
			}
			// Connect with client
			Client client = new Client();
			if (client.connect()) {
				AgentResource resource = new AgentResource(client);
				Runnable[] wrapper = new Runnable[1];
				Workspace workspace = new Workspace(new Resources(resource)) {
					@Override
					public void cleanup() {
						super.cleanup();
						Runnable cleanupAction = wrapper[0];
						if (cleanupAction != null)
							cleanupAction.run();
					}
				};
				// Only schedule updates to the agent resource if the workspace is loaded.
				if (RecafUI.getController().setWorkspace(workspace)) {
					resource.populateSystemIgnores();
					ScheduledExecutorService service = ThreadPoolFactory.newScheduledThreadPool("agent-resource");
					ScheduledFuture<?> agentResourceUpdateFuture =
							service.scheduleAtFixedRate(resource::updateClassList, 1, 1, TimeUnit.SECONDS);
					wrapper[0] = () -> {
						agentResourceUpdateFuture.cancel(true);
						service.shutdownNow();
					};
				}
			} else {
				logger.error("Could not connect to agent server, client connect gave 'false'");
			}
		} catch (AgentLoadException ex) {
			logger.error("Agent on remote VM '{}' could not be loaded", item, ex);
		} catch (AgentInitializationException ex) {
			logger.error("Agent on remote VM '{}' crashed on initialization", item, ex);
		} catch (IOException ex) {
			logger.error("IO error when loading agent to remote VM '{}'", item, ex);
		}
	}

	/**
	 * Comparator for {@link VirtualMachineDescriptor} using {@link #mapToMainClass(VirtualMachineDescriptor)}.
	 */
	class DescriptorComparator implements Comparator<VirtualMachineDescriptor> {
		@Override
		public int compare(VirtualMachineDescriptor o1, VirtualMachineDescriptor o2) {
			String k1 = virtualMachineMainClassMap.getOrDefault(o1, o1.displayName());
			String k2 = virtualMachineMainClassMap.getOrDefault(o2, o2.displayName());
			return k1.compareTo(k2);
		}
	}

	class VMCell extends ListCell<VirtualMachineDescriptor> {
		@Override
		protected void updateItem(VirtualMachineDescriptor item, boolean empty) {
			super.updateItem(item, empty);
			if (item == null || empty) {
				setGraphic(null);
			} else {
				setGraphic(createGraphic(item));
			}
		}

		private Node createGraphic(VirtualMachineDescriptor item) {
			VirtualMachine virtualMachine = virtualMachineMap.get(item);
			Exception attachException = virtualMachineFailureMap.get(item);
			VBox vertical = new VBox();
			vertical.setPadding(new Insets(5));
			vertical.setSpacing(5);
			HBox horizontal = new HBox();
			horizontal.setAlignment(Pos.CENTER_LEFT);
			horizontal.setSpacing(15);
			VBox buttons = new VBox();
			Label header = new Label(virtualMachinePidMap.get(item) + " - " + virtualMachineMainClassMap.get(item));
			header.getStyleClass().add("h2");
			vertical.getChildren().addAll(header, horizontal);
			Circle circle = new Circle();
			circle.radiusProperty().bind(horizontal.heightProperty().divide(3));
			String message;
			// TODO: Translatable text
			if (virtualMachine != null) {
				Properties properties = virtualMachinePropertiesMap.get(item);
				if (properties != null) {
					circle.setFill(Color.GREEN);
					String vmName = properties.getProperty("java.vm.name", "Unknown VM distribution");
					String vmVersion = properties.getProperty("java.version", "Unknown VM version");
					message = "VM: " + vmName + "\n" +
							"Version: " + vmVersion;
				} else {
					circle.setFill(Color.ORANGE);
					message = "VM supports attach\nFailed to read remote properties";
				}
			} else if (attachException != null) {
				boolean attachFailure = attachException instanceof AttachNotSupportedException;
				circle.setFill(attachFailure ? Color.RED : Color.ORANGE);
				String cause = attachFailure ? "VM does not support attach" : "IO error reading from VM";
				message = "Failed to attach:\n" + cause;
				buttons.setDisable(true);
			} else {
				throw new IllegalStateException("VM Desc has no associated VM, but also no attach failure reason");
			}
			horizontal.getChildren().addAll(buttons, circle, new Label(message));
			buttons.getChildren().add(new ActionButton("Connect", () -> connect(item)));
			return vertical;
		}
	}
}