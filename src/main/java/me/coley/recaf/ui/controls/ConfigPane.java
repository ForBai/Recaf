package me.coley.recaf.ui.controls;

import javafx.scene.Node;
import javafx.scene.control.*;
import me.coley.recaf.config.*;
import me.coley.recaf.control.gui.GuiController;

import java.util.*;
import java.util.function.Function;

/**
 * Pane for some config.
 *
 * @author Matt
 */
public class ConfigPane extends ColumnPane {
	private final Map<String, Function<FieldWrapper, Node>> editorOverrides = new HashMap<>();
	private boolean hideUnsupported;

	/**
	 * @param controller
	 * 		Gui controller.
	 * @param config
	 * 		Display config.
	 */
	public ConfigPane(GuiController controller, ConfDisplay config) {
		editorOverrides.put("display.language", LanguageCombo::new);
		editorOverrides.put("display.fontsize", v -> new FontSlider(controller, v));
		editorOverrides.put("display.textstyle", v -> new ThemeCombo(controller, v));
		editorOverrides.put("display.appstyle", v -> new StyleCombo(controller, v));
		editorOverrides.put("display.classmode", EnumComboBox::new);
		editorOverrides.put("display.filemode", EnumComboBox::new);
		setupConfigControls(config);
	}

	/**
	 * @param controller
	 * 		Gui controller.
	 * @param config
	 * 		Keybind config.
	 */
	public ConfigPane(GuiController controller, ConfKeybinding config) {
		editorOverrides.put("binding.close", KeybindField::new);
		editorOverrides.put("binding.saveapp", KeybindField::new);
		editorOverrides.put("binding.save", KeybindField::new);
		editorOverrides.put("binding.undo", KeybindField::new);
		editorOverrides.put("binding.gotodef", KeybindField::new);
		setupConfigControls(config);
	}

	/**
	 * @param controller
	 * 		Gui controller.
	 * @param config
	 * 		Decompiler config.
	 */
	public ConfigPane(GuiController controller, ConfDecompile config) {
		// TODO: When the decompiler is changed, switch options are displayed
		editorOverrides.put("decompile.decompiler", EnumComboBox::new);
		hideUnsupported = true;
		setupConfigControls(config);
	}

	private void setupConfigControls(Config config) {
		for(FieldWrapper field : config.getConfigFields()) {
			// Skip hidden values
			if(field.hidden())
				continue;
			// Check for override editor
			SubLabeled label = new SubLabeled(field.name(), field.description());
			if(editorOverrides.containsKey(field.key())) {
				// Add label/editor
				Node editor = editorOverrides.get(field.key()).apply(field);
				add(label, editor);
			} else if(!hideUnsupported) {
				// TODO: Create default editor for basic types
				add(label, new Label("Unsupported: " + config.getName()));
			}
		}
	}
}