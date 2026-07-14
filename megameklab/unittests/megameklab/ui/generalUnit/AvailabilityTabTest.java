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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import megamek.client.ratgenerator.MissionRole;
import megamek.common.equipment.Engine;
import megamek.common.equipment.EquipmentType;
import megamek.common.interfaces.ITechManager;
import megamek.common.units.BipedMek;
import megamek.common.units.Entity;
import megamek.common.units.ForceGeneratorAvailability;
import megamek.common.units.Mek;
import megameklab.ui.EntitySource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Builds the real Availability tab against a real unit.
 * <p>
 * The table model and the save path are tested elsewhere, but neither would notice if the tab itself threw while being
 * built or refreshed. Swing panels can be constructed without a display, so this runs headless and would catch exactly
 * that.
 * </p>
 */
class AvailabilityTabTest {

    @BeforeAll
    static void beforeAll() {
        EquipmentType.initializeTypes();
    }

    /** The tab only ever asks its source for the unit. */
    private record StubEntitySource(Entity entity) implements EntitySource {
        @Override
        public Entity getEntity() {
            return entity;
        }

        @Override
        public void createNewUnit(long entityType, boolean isPrimitive, boolean isIndustrial, Entity oldUnit) {
            throw new UnsupportedOperationException("The Availability tab never creates units");
        }

        @Override
        public ITechManager getTechManager() {
            return null;
        }
    }

    private static Mek buildMek() {
        Mek mek = new BipedMek();
        mek.setChassis("Grimjack");
        mek.setModel("GRM-1A");
        mek.setYear(3049);
        mek.setWeight(20.0);
        mek.setEngine(new Engine(100, Engine.NORMAL_ENGINE, 0));

        return mek;
    }

    @Test
    void theTabBuildsForAUnitWithNoAvailability() {
        // The common case: a unit that has never been near this tab
        assertDoesNotThrow(() -> new AvailabilityTab(new StubEntitySource(buildMek())));
    }

    @Test
    void theTabLoadsWhatTheUnitAlreadyDeclares() {
        Mek mek = buildMek();
        mek.setForceGeneratorAvailability(List.of(ForceGeneratorAvailability.parse("FS:5,LA:3")));
        mek.setMissionRoles("fire_support");

        AvailabilityTab tab = new AvailabilityTab(new StubEntitySource(mek));

        assertNotNull(tab);
        // The unit declared two factions, so the table should be showing two rows
        assertEquals(2, tab.getTableModel().getRowCount());
        assertEquals("FS", tab.getTableModel().getRow(0).factionCode());
        assertEquals(5, tab.getTableModel().getRow(0).availability());
    }

    @Test
    void refreshingAfterTheYearChangesDoesNotThrow() {
        // The introduction year lives on Basic Info, so it can change under this tab at any time
        Mek mek = buildMek();
        mek.setForceGeneratorAvailability(List.of(ForceGeneratorAvailability.parse("FS:5")));
        AvailabilityTab tab = new AvailabilityTab(new StubEntitySource(mek));

        mek.setYear(3150);

        assertDoesNotThrow(tab::refresh);
    }

    @Test
    void onlyRolesThatMeanSomethingForTheUnitTypeAreOffered() {
        // A Mek has no business being offered "mek carrier" or "paratrooper". MissionRole.fitsUnitType() already knows
        // which roles apply where, so the tab asks it rather than showing all fifty.
        AvailabilityTab tab = new AvailabilityTab(new StubEntitySource(buildMek()));

        assertTrue(tab.isRoleOffered(MissionRole.FIRE_SUPPORT), "A Mek can be fire support");
        assertTrue(tab.isRoleOffered(MissionRole.URBAN), "A Mek can be an urban unit");
        assertFalse(tab.isRoleOffered(MissionRole.MEK_CARRIER), "A Mek does not carry Meks");
        assertFalse(tab.isRoleOffered(MissionRole.PARATROOPER), "Paratrooper is an infantry role");
    }

    @Test
    void aRoleTheFileDeclaresIsShownEvenIfItDoesNotFit() {
        // Quietly dropping something out of somebody's file is not this tab's job. Show it, warn, let them decide.
        Mek mek = buildMek();
        mek.setMissionRoles("fire_support,paratrooper");

        AvailabilityTab tab = new AvailabilityTab(new StubEntitySource(mek));

        assertTrue(tab.isRoleOffered(MissionRole.PARATROOPER),
              "A role the file declares must stay visible so the player can remove it");
        assertTrue(tab.getMismatchedRoles().contains(MissionRole.PARATROOPER),
              "It should be flagged as not applying to this unit type");
        assertFalse(tab.getMismatchedRoles().contains(MissionRole.FIRE_SUPPORT));
    }

    @Test
    void theTabBuildsForAHandEditedFileItDoesNotOffer() {
        // The tab has no +/- control, but a hand-edited file may carry those. Opening such a unit must not throw.
        Mek mek = buildMek();
        mek.setForceGeneratorAvailability(List.of(ForceGeneratorAvailability.parse("CJF:5+,CSA:2-")));

        assertDoesNotThrow(() -> new AvailabilityTab(new StubEntitySource(mek)));
    }
}
