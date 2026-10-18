package com.mejta.mejtacore.ui;

import java.util.function.Consumer;
import java.util.function.Predicate;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Simple single-line text editor that returns to its parent screen. */
public class TextInputScreen extends Screen {
	private final Screen parent;
	private final String initialValue;
	private final Predicate<String> validator;
	private final Consumer<String> onSubmit;
	private final Component hint;
	private EditBox input;
	private Button doneButton;

	public TextInputScreen(Screen parent, Component title, String initialValue, Component hint,
			Predicate<String> validator, Consumer<String> onSubmit) {
		super(title);
		this.parent = parent;
		this.initialValue = initialValue;
		this.hint = hint;
		this.validator = validator;
		this.onSubmit = onSubmit;
	}

	@Override
	protected void init() {
		String value = input != null ? input.getValue() : initialValue;
		input = new EditBox(font, width / 2 - 100, height / 2 - 10, 200, 20, getTitle());
		input.setMaxLength(256);
		input.setValue(value);
		input.setResponder(text -> doneButton.active = isValid(text));
		addRenderableWidget(input);

		doneButton = addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> submit())
				.bounds(width / 2 - 100, height / 2 + 20, 98, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
				.bounds(width / 2 + 2, height / 2 + 20, 98, 20).build());
		doneButton.active = isValid(value);
		setInitialFocus(input);
	}

	/** Current text in the input box. */
	public String currentValue() {
		return input == null ? initialValue : input.getValue();
	}

	private boolean isValid(String text) {
		try {
			return validator.test(text);
		} catch (RuntimeException e) {
			return false;
		}
	}

	private void submit() {
		if (isValid(input.getValue())) {
			onSubmit.accept(input.getValue());
			onClose();
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isConfirmation() && input.isFocused()) {
			submit();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(font, getTitle(), width / 2, height / 2 - 40, 0xFFFFFFFF);
		Component status = isValid(input.getValue()) ? hint : Component.literal("Invalid value").withStyle(ChatFormatting.RED);
		graphics.centeredText(font, status, width / 2, height / 2 - 25, 0xFFA0A0A0);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean isInGameUi() {
		return true;
	}
}
