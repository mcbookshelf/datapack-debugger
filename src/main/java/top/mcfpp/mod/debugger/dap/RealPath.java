package top.mcfpp.mod.debugger.dap;

/**
 * Represents a path to a resource in the Minecraft environment.
 * This record stores information about the location of a file and its type.
 *
 * @param path The string representation of the path to the resource
 * @param kind The type of resource (e.g. ZIP archive)
 */
public record RealPath(String path, Kind kind) {
    /**
     * Enumeration of supported resource container types.
     * Currently supports ZIP archives which are commonly used for datapacks.
     */
    enum Kind {
        /** Indicates the resource is stored in a ZIP archive */
        ZIP,
    }

}
