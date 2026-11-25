package de.thm.mni.compilerbau.phases._05_varalloc;

/**
 * This class describes the stack frame layout of a procedure.
 * It contains the sizes of the various subareas and provides methods to retrieve information about the stack frame required to generate code for the procedure.
 */
public class StackLayout {
    // The following values have to be set in phase 5
    public Integer argumentAreaSize = null;
    public Integer localVarAreaSize = null;
        public Integer outgoingAreaSize = null;

    /**
     * @return The total size of the stack frame described by this object.
     */
    public int frameSize() {
        if (argumentAreaSize == null || localVarAreaSize == null || outgoingAreaSize == null) {
            throw new IllegalStateException("Stack layout sizes are not fully initialized.");
        }
        return localVarAreaSize + 8 + outgoingAreaSize; // 4 bytes for old FP + 4 bytes for old return address
    }

    /**
     * @return The offset (starting from the new stack pointer) where the old frame pointer is stored in this stack frame.
     */
    public int oldFramePointerOffset() {
        return outgoingAreaSize + 4  ; // Old FP is stored after local variables and outgoing area
    }

    /**
     * @return The offset (starting from the new frame pointer) where the old return address is stored in this stack frame.
     */
    public int oldReturnAddressOffset() {
        return -(localVarAreaSize + 8); // Old return address is stored immediately after the old FP
    }
}
