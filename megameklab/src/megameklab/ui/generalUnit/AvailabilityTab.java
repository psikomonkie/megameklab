/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMekLab.
 *
 * MegaMekLab is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMekLab is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMekLab was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megameklab.ui.generalUnit;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.io.Serial;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.DefaultTableCellRenderer;

import megamek.client.ratgenerator.AvailabilityRating;
import megamek.client.ratgenerator.ChassisRecord;
import megamek.client.ratgenerator.FactionRecord;
import megamek.client.ratgenerator.MissionRole;
import megamek.client.ratgenerator.RATGenerator;
import megamek.client.ui.dialogs.UnitLoadingDialog;
import megamek.client.ui.util.UIUtil;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Entity;
import megamek.common.units.ForceGeneratorAvailability;
import megamek.common.units.UnitType;
import megameklab.ui.EntitySource;
import megameklab.ui.dialog.AddFactionsDialog;
import megameklab.ui.dialog.MegaMekLabUnitSelectorDialog;
import megameklab.ui.generalUnit.AvailabilityTableModel.AvailabilityRow;
import megameklab.ui.util.ITab;
import megameklab.ui.util.RefreshListener;
import megameklab.util.AvailabilityCalibration;

/**
 * Lets a player say which factions field a custom unit, and how often, so it turns up in generated forces.
 * <p>
 * The tab is built around the order a player actually works in: the unit is already built and its introduction year is
 * already set on Basic Info, so this tab comes last. List the factions, set the number, and only then worry about year
 * ranges.
 * </p>
 * <p>
 * The number runs 0 to 10 on a base-2 log scale and means nothing on its own, so the slider says it in words and the
 * tab names canon designs of about the same commonness. That is the difference between a considered number and a
 * guess.
 * </p>
 */
public class AvailabilityTab extends ITab {
    @Serial
    private static final long serialVersionUID = 1L;

    /** What a new faction starts at. 6 is what the canon data uses when the source books give no hint either way. */
    private static final int DEFAULT_AVAILABILITY = 6;
    private static final int MIN_AVAILABILITY = 0;
    private static final int MAX_AVAILABILITY = 10;
    private static final int MIN_YEAR = 1950;
    private static final int MAX_YEAR = 3200;
    private static final int TABLE_WIDTH = 640;
    private static final int TABLE_HEIGHT = 200;
    private static final int ROLE_COLUMNS = 4;

    private RefreshListener refresh;

    private final AvailabilityTableModel tableModel = new AvailabilityTableModel();
    private final JTable factionTable = new JTable(tableModel);

    private final JLabel headerLabel = new JLabel();
    private final JLabel warningLabel = new JLabel();

    private final JSlider availabilitySlider = new JSlider(MIN_AVAILABILITY, MAX_AVAILABILITY);
    private final JLabel availabilityWordLabel = new JLabel();
    private final JLabel comparableLabel = new JLabel();
    private final JSpinner fromYearSpinner = new JSpinner(new SpinnerNumberModel(MIN_YEAR, MIN_YEAR, MAX_YEAR, 1));
    private final JSpinner toYearSpinner = new JSpinner(new SpinnerNumberModel(MIN_YEAR, MIN_YEAR, MAX_YEAR, 1));
    private final JCheckBox neverStopsCheckBox = new JCheckBox("never stops");
    private final JPanel editorPanel = new JPanel();

    private final Map<MissionRole, JCheckBox> roleCheckBoxes = new LinkedHashMap<>();
    private final JPanel rolesPanel = new JPanel(new GridLayout(0, ROLE_COLUMNS));
    /** Roles the unit file declares that do not apply to this unit type. Shown, not dropped. */
    private final Set<MissionRole> mismatchedRoles = EnumSet.noneOf(MissionRole.class);

    private final JButton addButton = new JButton("+ Add factions...");
    private final JButton removeButton = new JButton("- Remove");
    private final JButton copyFromUnitButton = new JButton("Copy numbers from a unit...");

    /** Guards the listeners while the editor is being filled in from the selected row. */
    private boolean updatingEditor = false;

    public AvailabilityTab(EntitySource eSource) {
        super(eSource);
        buildLayout();
        refresh();
    }

    public void addRefreshedListener(RefreshListener listener) {
        refresh = listener;
    }

    /**
     * The rows the tab is showing.
     *
     * @return the table model
     */
    public AvailabilityTableModel getTableModel() {
        return tableModel;
    }

    /**
     * Whether a role is on offer for this unit. A role that does not apply to the unit type is hidden, unless the unit
     * file already declares it.
     *
     * @param role the role to check
     *
     * @return {@code true} if the player can see and tick it
     */
    public boolean isRoleOffered(MissionRole role) {
        JCheckBox checkBox = roleCheckBoxes.get(role);

        return (checkBox != null) && checkBox.isVisible();
    }

    /**
     * The roles the unit file declares that do not apply to this unit type. The Force Generator ignores these.
     *
     * @return the mismatched roles
     */
    public Set<MissionRole> getMismatchedRoles() {
        return Set.copyOf(mismatchedRoles);
    }

    /**
     * Reloads the tab from the unit. The introduction year lives on Basic Info, so it can change under this tab; the
     * faction list and the warnings are rebuilt every time.
     */
    public void refresh() {
        Entity entity = getEntity();

        headerLabel.setText(entity.getShortNameRaw()
              + "  -  introduced " + entity.getYear()
              + "  -  " + (isCanonUnit() ? "canon unit" : "custom unit"));

        tableModel.loadFrom(entity.getForceGeneratorAvailability(), this::factionNameOf);
        tableModel.markStaleFactions(activeFactionCodes());
        loadMissionRoles(entity.getMissionRoles());

        updateWarnings();
        updateEditorFromSelection();
    }

    private void buildLayout() {
        setLayout(new BorderLayout());

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        headerPanel.add(headerLabel, BorderLayout.NORTH);
        warningLabel.setForeground(Color.RED);
        headerPanel.add(warningLabel, BorderLayout.CENTER);
        add(headerPanel, BorderLayout.NORTH);

        JPanel centrePanel = new JPanel();
        centrePanel.setLayout(new BoxLayout(centrePanel, BoxLayout.Y_AXIS));
        centrePanel.add(buildFactionPanel());
        centrePanel.add(buildEditorPanel());
        centrePanel.add(buildRolesPanel());
        add(new JScrollPane(centrePanel), BorderLayout.CENTER);
    }

    private JPanel buildFactionPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Who fields this unit?"));

        factionTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        factionTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updateEditorFromSelection();
            }
        });
        factionTable.setDefaultRenderer(Object.class, new StaleRowRenderer());
        JScrollPane tableScroll = new JScrollPane(factionTable);
        tableScroll.setPreferredSize(UIUtil.scaleForGUI(TABLE_WIDTH, TABLE_HEIGHT));
        panel.add(tableScroll, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        addButton.addActionListener(event -> addFactions());
        removeButton.addActionListener(event -> removeSelectedFaction());
        copyFromUnitButton.addActionListener(event -> copyNumbersFromUnit());
        copyFromUnitButton.setToolTipText("Start from a design you already know, rather than from a blank table.");
        buttonPanel.add(addButton);
        buttonPanel.add(removeButton);
        buttonPanel.add(copyFromUnitButton);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        return panel;
    }

    /**
     * Fills the table from a canon design's own numbers.
     * <p>
     * Nobody has intuition for a base-2 log scale, so the surest way to a sane table is to start from a design whose
     * commonness the player already understands and adjust from there.
     * </p>
     */
    private void copyNumbersFromUnit() {
        UnitLoadingDialog unitLoadingDialog = new UnitLoadingDialog(null);
        unitLoadingDialog.setVisible(true);

        Entity chosenEntity;
        try {
            MegaMekLabUnitSelectorDialog selector =
                  new MegaMekLabUnitSelectorDialog(null, unitLoadingDialog, false);
            chosenEntity = selector.getChosenEntity();
        } finally {
            unitLoadingDialog.setVisible(false);
        }

        if (chosenEntity == null) {
            return;
        }

        List<AvailabilityRating> ratings = AvailabilityCalibration.ratingsOf(chassisKeyOf(chosenEntity),
              getEntity().getYear());

        if (ratings.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                  "The Force Generator has no availability for " + chosenEntity.getShortNameRaw() + " in "
                        + getEntity().getYear() + ", so there is nothing to copy.",
                  "Nothing to copy",
                  JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        for (AvailabilityRating rating : ratings) {
            tableModel.addRow(new AvailabilityRow(rating.getFactionCode(),
                  factionNameOf(rating.getFactionCode()),
                  rating.getAvailability(),
                  ForceGeneratorAvailability.UNSPECIFIED_YEAR,
                  ForceGeneratorAvailability.UNSPECIFIED_YEAR,
                  false));
        }

        tableModel.markStaleFactions(activeFactionCodes());
        writeBack();
    }

    /**
     * Builds the key the Force Generator files a design's chassis under.
     *
     * @param entity the design
     *
     * @return the chassis key, e.g. "Archer[Mek]"
     */
    private static String chassisKeyOf(Entity entity) {
        String key = entity.getChassis() + '[' + UnitType.getTypeName(entity.getUnitType()) + ']';
        if (!entity.isOmni()) {
            return key;
        }

        return key + (entity.isClan() ? "ClanOmni" : "ISOmni");
    }

    private JPanel buildEditorPanel() {
        editorPanel.setLayout(new BoxLayout(editorPanel, BoxLayout.Y_AXIS));
        editorPanel.setBorder(BorderFactory.createTitledBorder("Selected faction"));

        availabilitySlider.setMajorTickSpacing(2);
        availabilitySlider.setPaintTicks(true);
        availabilitySlider.setPaintLabels(true);
        availabilitySlider.setSnapToTicks(false);
        availabilitySlider.addChangeListener(event -> onAvailabilityChanged());

        JPanel sliderPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        sliderPanel.add(new JLabel("How common?"));
        sliderPanel.add(availabilitySlider);
        sliderPanel.add(availabilityWordLabel);
        editorPanel.add(sliderPanel);

        JPanel comparablePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        comparablePanel.add(comparableLabel);
        editorPanel.add(comparablePanel);

        JPanel yearPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        yearPanel.add(new JLabel("Years: from"));
        yearPanel.add(fromYearSpinner);
        yearPanel.add(new JLabel("to"));
        yearPanel.add(toYearSpinner);
        yearPanel.add(neverStopsCheckBox);
        fromYearSpinner.addChangeListener(event -> onYearsChanged());
        toYearSpinner.addChangeListener(event -> onYearsChanged());
        neverStopsCheckBox.addActionListener(event -> onYearsChanged());
        editorPanel.add(yearPanel);

        return editorPanel;
    }

    private JPanel buildRolesPanel() {
        rolesPanel.setBorder(BorderFactory.createTitledBorder("Mission roles (optional)"));

        for (MissionRole role : MissionRole.values()) {
            JCheckBox checkBox = new JCheckBox(role.toString().replace('_', ' '));
            checkBox.addActionListener(event -> writeBack());
            roleCheckBoxes.put(role, checkBox);
            rolesPanel.add(checkBox);
        }

        return rolesPanel;
    }

    // --- Faction list -------------------------------------------------------------------------------------------

    private void addFactions() {
        List<String> alreadyChosen = new ArrayList<>();
        for (AvailabilityRow row : tableModel.getRows()) {
            alreadyChosen.add(row.factionCode());
        }

        AddFactionsDialog dialog = new AddFactionsDialog(this, getEntity().getYear(), alreadyChosen);
        dialog.setVisible(true);

        for (String factionCode : dialog.getChosenFactionCodes()) {
            tableModel.addRow(new AvailabilityRow(factionCode,
                  factionNameOf(factionCode),
                  DEFAULT_AVAILABILITY,
                  ForceGeneratorAvailability.UNSPECIFIED_YEAR,
                  ForceGeneratorAvailability.UNSPECIFIED_YEAR,
                  false));
        }

        tableModel.markStaleFactions(activeFactionCodes());
        writeBack();
    }

    private void removeSelectedFaction() {
        int selected = factionTable.getSelectedRow();
        if (selected < 0) {
            return;
        }

        tableModel.removeRow(selected);
        writeBack();
    }

    // --- Editor strip -------------------------------------------------------------------------------------------

    private void updateEditorFromSelection() {
        int selected = factionTable.getSelectedRow();
        boolean hasSelection = (selected >= 0) && (selected < tableModel.getRowCount());

        availabilitySlider.setEnabled(hasSelection);
        fromYearSpinner.setEnabled(hasSelection);
        toYearSpinner.setEnabled(hasSelection);
        neverStopsCheckBox.setEnabled(hasSelection);

        if (!hasSelection) {
            availabilityWordLabel.setText("");
            comparableLabel.setText("Select a faction to set how common this unit is.");
            return;
        }

        AvailabilityRow row = tableModel.getRow(selected);

        updatingEditor = true;
        availabilitySlider.setValue(row.availability());
        int fromYear = (row.fromYear() == ForceGeneratorAvailability.UNSPECIFIED_YEAR)
              ? getEntity().getYear()
              : row.fromYear();
        fromYearSpinner.setValue(Math.clamp(fromYear, MIN_YEAR, MAX_YEAR));
        boolean neverStops = (row.toYear() == ForceGeneratorAvailability.UNSPECIFIED_YEAR);
        neverStopsCheckBox.setSelected(neverStops);
        toYearSpinner.setEnabled(!neverStops);
        toYearSpinner.setValue(Math.clamp(neverStops ? MAX_YEAR : row.toYear(), MIN_YEAR, MAX_YEAR));
        updatingEditor = false;

        updateAvailabilityText(row);
    }

    private void onAvailabilityChanged() {
        if (updatingEditor) {
            return;
        }

        int selected = factionTable.getSelectedRow();
        if (selected < 0) {
            return;
        }

        AvailabilityRow row = tableModel.getRow(selected).withAvailability(availabilitySlider.getValue());
        tableModel.setRow(selected, row);
        updateAvailabilityText(row);
        writeBack();
    }

    private void onYearsChanged() {
        if (updatingEditor) {
            return;
        }

        int selected = factionTable.getSelectedRow();
        if (selected < 0) {
            return;
        }

        toYearSpinner.setEnabled(!neverStopsCheckBox.isSelected());

        int fromYear = (int) fromYearSpinner.getValue();
        int toYear = neverStopsCheckBox.isSelected()
              ? ForceGeneratorAvailability.UNSPECIFIED_YEAR
              : (int) toYearSpinner.getValue();

        tableModel.setRow(selected, tableModel.getRow(selected).withYears(fromYear, toYear));
        updateWarnings();
        writeBack();
    }

    /**
     * Says the number in words and names canon designs of about the same commonness. Without this the number is a
     * guess: nobody has intuition for a base-2 log scale.
     *
     * @param row the row being edited
     */
    private void updateAvailabilityText(AvailabilityRow row) {
        availabilityWordLabel.setText(row.availability() + "  " + AvailabilityCalibration.describe(row.availability()));

        List<String> comparable = AvailabilityCalibration.comparableUnits(getEntity().getUnitType(),
              row.factionCode(),
              getEntity().getYear(),
              row.availability());

        if (comparable.isEmpty()) {
            comparableLabel.setText(" ");
            return;
        }

        StringJoiner names = new StringJoiner(", ");
        comparable.forEach(names::add);
        comparableLabel.setText("At " + row.factionCode() + ":" + row.availability()
              + " this is about as common as: " + names);
    }

    // --- Mission roles ------------------------------------------------------------------------------------------

    /**
     * Shows only the roles that mean anything for this unit type. A Mek has no business being offered "mek carrier" or
     * "paratrooper", and the Force Generator would ignore them anyway.
     * <p>
     * A role the unit file declares that does not fit is kept, selected and visible, so the player can see it and
     * decide. Quietly dropping something out of somebody's file is not this tab's job.
     * </p>
     */
    private void loadMissionRoles(String missionRoles) {
        Set<MissionRole> chosen = EnumSet.noneOf(MissionRole.class);
        for (String roleName : missionRoles.split(",")) {
            String trimmed = roleName.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            MissionRole role = MissionRole.parseRole(trimmed);
            if (role != null) {
                chosen.add(role);
            }
        }

        int unitType = getEntity().getUnitType();
        mismatchedRoles.clear();

        for (Map.Entry<MissionRole, JCheckBox> entry : roleCheckBoxes.entrySet()) {
            MissionRole role = entry.getKey();
            JCheckBox checkBox = entry.getValue();
            boolean isSelected = chosen.contains(role);
            boolean fits = role.fitsUnitType(unitType);

            checkBox.setSelected(isSelected);
            checkBox.setVisible(fits || isSelected);

            if (isSelected && !fits) {
                mismatchedRoles.add(role);
            }
        }

        rolesPanel.revalidate();
        rolesPanel.repaint();
    }

    private String missionRolesText() {
        StringJoiner roles = new StringJoiner(",");
        for (Map.Entry<MissionRole, JCheckBox> entry : roleCheckBoxes.entrySet()) {
            if (entry.getValue().isSelected()) {
                roles.add(entry.getKey().toString());
            }
        }

        return roles.toString();
    }

    // --- Warnings -----------------------------------------------------------------------------------------------

    /**
     * Tells the player when what they are doing will silently not work. Everything here is a rule a hand-editing
     * player would only discover by reading megamek.log, which is not a reasonable ask.
     */
    private void updateWarnings() {
        List<String> warnings = new ArrayList<>();

        if (isCanonUnit()) {
            warnings.add("This is a canon unit. The Force Generator will IGNORE everything on this tab. "
                  + "Save it under a new model name to make it a custom variant.");
        } else if (isCanonChassis()) {
            warnings.add("This is a custom variant of a canon chassis. Factions that already field the chassis keep "
                  + "their canon rating, so your number only decides which variant they get. Factions that do not "
                  + "field it will now get this variant.");
        }

        for (AvailabilityRow row : tableModel.getRows()) {
            if ((row.fromYear() != ForceGeneratorAvailability.UNSPECIFIED_YEAR)
                  && (row.fromYear() < getEntity().getYear())) {
                warnings.add(row.factionCode() + " starts in " + row.fromYear()
                      + ", but the unit does not exist until " + getEntity().getYear() + ".");
            }
        }

        if (!mismatchedRoles.isEmpty()) {
            StringJoiner roleNames = new StringJoiner(", ");
            mismatchedRoles.forEach(role -> roleNames.add(role.toString().replace('_', ' ')));
            warnings.add("These mission roles do not apply to this unit type and will be ignored: " + roleNames + ".");
        }

        if (tableModel.hasStaleRows()) {
            warnings.add("Some factions in the table do not exist in " + getEntity().getYear()
                  + ". Those entries will never be used.");
        }

        if (warnings.isEmpty()) {
            warningLabel.setText(" ");
            return;
        }

        StringJoiner text = new StringJoiner("<br>", "<html>", "</html>");
        warnings.forEach(text::add);
        warningLabel.setText(text.toString());
    }

    /**
     * Entity.isCanon() is stamped when the unit is loaded, so it goes stale the moment the player renames the unit,
     * which is exactly how a canon design becomes a custom variant. Ask by the current name instead.
     *
     * @return true if the unit's current name is a canon unit
     */
    private boolean isCanonUnit() {
        return MekFileParser.isCanonUnitName(getEntity().getShortNameRaw());
    }

    private boolean isCanonChassis() {
        RATGenerator ratGenerator = RATGenerator.getInstance();
        if (!ratGenerator.isInitialized()) {
            return false;
        }

        for (ChassisRecord chassisRecord : ratGenerator.getChassisList()) {
            if (chassisRecord.getChassis().equalsIgnoreCase(getEntity().getChassis())) {
                return true;
            }
        }

        return false;
    }

    // --- Force Generator lookups --------------------------------------------------------------------------------

    private String factionNameOf(String factionCode) {
        RATGenerator ratGenerator = RATGenerator.getInstance();
        if (!ratGenerator.isInitialized()) {
            return factionCode;
        }

        FactionRecord factionRecord = ratGenerator.getFaction(factionCode);

        return (factionRecord == null) ? factionCode : factionRecord.getName(getEntity().getYear());
    }

    private Set<String> activeFactionCodes() {
        RATGenerator ratGenerator = RATGenerator.getInstance();
        Set<String> codes = new HashSet<>(AddFactionsDialog.UMBRELLA_CODES);
        if (!ratGenerator.isInitialized()) {
            return codes;
        }

        for (FactionRecord factionRecord : ratGenerator.getFactionList()) {
            if (factionRecord.isActiveInYear(getEntity().getYear())) {
                codes.add(factionRecord.getKey());
            }
        }

        return codes;
    }

    // --- Persistence --------------------------------------------------------------------------------------------

    /**
     * Stores the tab's state on the unit. MegaMekLab's savers write the entity out, so nothing else is needed to get
     * these onto disk.
     */
    private void writeBack() {
        List<ForceGeneratorAvailability> entries = tableModel.toAvailabilityEntries();
        getEntity().setForceGeneratorAvailability(entries);
        getEntity().setMissionRoles(missionRolesText());

        updateWarnings();

        if (refresh != null) {
            refresh.refreshStatus();
        }
    }

    /** Highlights rows whose faction does not exist in the unit's year. */
    private static class StaleRowRenderer extends DefaultTableCellRenderer {
        @Serial
        private static final long serialVersionUID = 1L;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
              boolean hasFocus, int row, int column) {
            Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            AvailabilityTableModel model = (AvailabilityTableModel) table.getModel();
            if (model.getRow(row).stale() && !isSelected) {
                component.setForeground(Color.RED);
            } else if (!isSelected) {
                component.setForeground(table.getForeground());
            }

            return component;
        }
    }

    public ComponentListener refreshOnShow = new ComponentAdapter() {
        @Override
        public void componentShown(ComponentEvent event) {
            refresh();
        }
    };
}
