package com.example.skiajni.html;

/** Computed style for a DOM element, with parsed/typed values. */
public final class Style {
    public int color = 0xFF1B1B1B;
    public int background = 0; // 0 = transparent
    public int borderColor = 0xFF000000;
    public float fontSize = 16f;
    public boolean bold = false;
    public int textAlign = 0; // 0=left,1=center,2=right
    public String display = "block"; // block | inline | flex | none
    public String flexDirection = "row"; // row | column
    public String justifyContent = "flex-start"; // flex-start | center | space-between
    public String alignItems = "stretch"; // stretch | center | flex-start | flex-end
    public String fontWeight = "normal";
    public float width = -1; // auto
    public float height = -1; // auto
    public float margin = 0, padding = 0;
    public float borderWidth = 0;
    public float borderRadius = 0;
    public float lineHeight = 1.4f;
    public float gap = 0;
    public boolean textTransformNone = true;
    public String textTransform = "none";
}
