package org.shadeledger.matcher;

import org.objectweb.asm.commons.Remapper;

/**
 * Applies a single Maven Shade "relocation" package-prefix rewrite to
 * internal (slash-separated) class names, mirroring what the shade
 * plugin's own relocator does for the simple (non-rawString) case.
 *
 * Also rewrites dotted-form string constants that start with the old
 * package name. Maven Shade does this too: code that loads a class by
 * name at runtime (ServiceLoader, Class.forName, and similar) often
 * carries the fully-qualified name as a plain string, not a type
 * reference, so relocation has to touch those constants as well or the
 * shaded jar breaks at runtime. Found by testing against log4j-core,
 * where a handful of classes (ClockFactory, PluginManager, and others
 * that look up implementations by name) failed the byte-for-byte check
 * until this was added.
 */
public class RelocationRemapper extends Remapper {

    private final String oldPrefix;
    private final String newPrefix;
    private final String oldDotted;
    private final String newDotted;

    public RelocationRemapper(String oldPackageDotted, String newPackageDotted) {
        this.oldPrefix = oldPackageDotted.replace('.', '/') + "/";
        this.newPrefix = newPackageDotted.replace('.', '/') + "/";
        this.oldDotted = oldPackageDotted;
        this.newDotted = newPackageDotted;
    }

    @Override
    public String map(String internalName) {
        if (internalName.startsWith(oldPrefix)) {
            return newPrefix + internalName.substring(oldPrefix.length());
        }
        return internalName;
    }

    @Override
    public Object mapValue(Object value) {
        if (value instanceof String) {
            String s = (String) value;
            if (s.equals(oldDotted)) {
                return newDotted;
            }
            if (s.startsWith(oldDotted + ".")) {
                return newDotted + s.substring(oldDotted.length());
            }
            return s;
        }
        return super.mapValue(value);
    }

    public boolean isInScope(String internalName) {
        return internalName.startsWith(oldPrefix);
    }
}
