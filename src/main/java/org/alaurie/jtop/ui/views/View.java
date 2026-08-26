package org.alaurie.jtop.ui.views;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.tui.event.KeyEvent;
import org.alaurie.jtop.ui.JTopApp;

/// Deep architectural interface for jtop views, hiding input handling and rendering behind a single seam.
public interface View {
    void render(Rect area, Buffer buffer, ViewContext context);
    boolean handleKey(KeyEvent keyEvent, ViewContext context, JTopApp app);
}
