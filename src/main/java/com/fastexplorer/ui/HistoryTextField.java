package com.fastexplorer.ui;

import com.fastexplorer.config.InputHistoryStore;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JTextField;
import javax.swing.text.Document;
import java.awt.Component;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;

public class HistoryTextField extends JComboBox<String> {

    private final String historyKey;
    private final boolean restoreLastValue;

    public HistoryTextField(String historyKey) {
        this(historyKey, false);
    }

    public HistoryTextField(String historyKey, boolean restoreLastValue) {
        this.historyKey = historyKey;
        this.restoreLastValue = restoreLastValue;
        setEditable(true);
        setPrototypeDisplayValue("MMMMMMMMMMMM");
        loadFromStore();
    }

    public String getText() {
        Object item = getEditor().getItem();
        return item != null ? item.toString() : "";
    }

    public void setText(String text) {
        getEditor().setItem(text != null ? text : "");
    }

    public JTextField getEditorField() {
        Component component = getEditor().getEditorComponent();
        return component instanceof JTextField textField ? textField : null;
    }

    public Document getDocument() {
        JTextField editor = getEditorField();
        return editor != null ? editor.getDocument() : null;
    }

    public void addEditorActionListener(ActionListener listener) {
        JTextField editor = getEditorField();
        if (editor != null) {
            editor.addActionListener(listener);
        }
    }

    public void commitHistory() {
        String value = getText().trim();
        if (value.isEmpty()) {
            return;
        }
        DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) getModel();
        for (int i = 0; i < model.getSize(); i++) {
            if (value.equals(model.getElementAt(i))) {
                model.removeElementAt(i);
                break;
            }
        }
        model.insertElementAt(value, 0);
        while (model.getSize() > InputHistoryStore.MAX_ENTRIES) {
            model.removeElementAt(model.getSize() - 1);
        }
        setText(value);
        persistHistory();
    }

    public void persistHistory() {
        InputHistoryStore.save(historyKey, getHistoryItems());
    }

    private List<String> getHistoryItems() {
        DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) getModel();
        List<String> items = new ArrayList<>(model.getSize());
        for (int i = 0; i < model.getSize(); i++) {
            items.add(model.getElementAt(i));
        }
        return items;
    }

    private void loadFromStore() {
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
        for (String entry : InputHistoryStore.load(historyKey)) {
            model.addElement(entry);
        }
        setModel(model);
        if (model.getSize() > 0) {
            if (restoreLastValue) {
                setSelectedIndex(0);
                setText(model.getElementAt(0));
            } else {
                setSelectedIndex(-1);
                setText("");
            }
        }
    }
}
