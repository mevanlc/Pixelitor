/*
 * Copyright 2026 Laszlo Balazs-Csiki and Contributors
 *
 * This file is part of Pixelitor. Pixelitor is free software: you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License, version 3 as published by the Free
 * Software Foundation.
 *
 * Pixelitor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Pixelitor. If not, see <http://www.gnu.org/licenses/>.
 */

package pixelitor.filters.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserPresetTest {
    @Test
    void loadsDefaultPreset(@TempDir File presetsRoot) throws IOException {
        File presetDir = new File(presetsRoot, "Test Tool");
        Files.createDirectories(presetDir.toPath());
        Files.writeString(presetDir.toPath().resolve("Default.txt"), "Size=42\n");

        PresetOwner owner = mock(PresetOwner.class);
        when(owner.getPresetDirName()).thenReturn("Test Tool");

        assertThat(UserPreset.loadDefault(owner, presetsRoot)).isTrue();
        verify(owner).loadUserPreset(argThat(preset -> preset.getInt("Size") == 42));
    }

    @Test
    void fallsBackToCaseInsensitiveDefaultPresetName(@TempDir File presetsRoot) throws IOException {
        File presetDir = new File(presetsRoot, "Test Tool");
        Files.createDirectories(presetDir.toPath());
        Files.writeString(presetDir.toPath().resolve("default.txt"), "Size=42\n");

        PresetOwner owner = mock(PresetOwner.class);
        when(owner.getPresetDirName()).thenReturn("Test Tool");

        assertThat(UserPreset.loadDefault(owner, presetsRoot)).isTrue();
        verify(owner).loadUserPreset(argThat(preset -> preset.getInt("Size") == 42));
    }

    @Test
    void exactDefaultPresetNameTakesPrecedence() {
        File caseInsensitiveMatch = new File("default.txt");
        File exactMatch = new File("Default.txt");

        assertThat(UserPreset.findDefaultFile(new File[]{caseInsensitiveMatch, exactMatch}))
            .isSameAs(exactMatch);
    }
}
