package top.mcfpp.mod.debugger.command;

import com.google.common.collect.Queues;
import com.mojang.brigadier.arguments.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandExecutionContext;
import static net.minecraft.server.command.CommandManager.literal;
import static net.minecraft.server.command.CommandManager.argument;

import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.mcfpp.mod.debugger.DatapackDebugger;
import top.mcfpp.mod.debugger.dap.DebuggerState;
import top.mcfpp.mod.debugger.dap.ScopeManager;

import java.util.Deque;

/**
 * Main command handler for the datapack debugging system.
 * Provides commands for setting breakpoints, stepping through code, and inspecting variables.
 */
public class BreakPointCommand {

    /** Indicates if a debug command is currently being executed */
    public static boolean isDebugCommand = false;
    /** Indicates if the debugger is currently active */
    public static boolean isDebugging = false;
    /** Controls whether debug mode is enabled */
    public static boolean debugMode = true;
    /** Number of steps to execute in step mode */
    public static int moveSteps = 0;
    /** Depth being currently debugged by the step over, to not go in deeper depth. -1 = no depth currently debugged */
    public static int stepOverDepth = -1;
    /** Whether the current debugging step is a step over */
    public static boolean isStepOver = false;
    /** Queue storing command execution contexts for debugging */
    public static final Deque<CommandExecutionContext<?>> storedCommandExecutionContext = Queues.newArrayDeque();
    /** Logger instance for this class */
    private static final org.slf4j.Logger LOGGER = DatapackDebugger.getLogger();

    /**
     * Initializes the breakpoint command system.
     * Registers all subcommands including:
     * - breakpoint: Sets a breakpoint
     * - continue: Continues execution
     * - step: Steps through code
     * - get: Retrieves variable values
     * - stack: Shows function call stack
     * - run: Executes commands
     * - clear: Clears debug state
     * - on/off: Toggles debug mode
     */
    public static void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("breakpoint")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(context -> {
                        if(!debugMode) return 1;
                        var server = context.getSource().getServer();
                        if(server != null){
                            var players = server.getPlayerManager().getPlayerList();
                            for (var player : players){
                                player.sendMessage(Text.translatable("datapack-debugger.commands.breakpoint.set"));
                            }
                        }
                        DebuggerState.get().triggerBreakpoint(context.getSource());
                        return 1;
                    })
                    .then(literal("step")
                            .executes(context -> {
                                step(1, context.getSource());
                                return 1;
                            })
                            .then(argument("lines", IntegerArgumentType.integer())
                                    .executes(context -> {
                                        final int lines = IntegerArgumentType.getInteger(context, "lines");
                                        step(lines, context.getSource());
                                        return 1;
                                    })
                            )
                    )
                    .then(literal("step_over")
                            .executes(context -> {
                                isStepOver = true;
                                step(1, context.getSource());
                                return 1;
                            })
                            .then(argument("lines", IntegerArgumentType.integer())
                                    .executes(context -> {
                                        isStepOver = true;
                                        final int lines = IntegerArgumentType.getInteger(context, "lines");
                                        step(lines, context.getSource());
                                        return 1;
                                    })
                            )
                    )
                    .then(literal("continue")
                            .executes(context -> {
                                context.getSource().sendFeedback(() -> Text.translatable("datapack-debugger.commands.breakpoint.move"), false);
                                continueExec(context.getSource());
                                return 1;
                            })
                    )
                    .then(literal("get")
                            .then(argument("key", StringArgumentType.string())
                                    .suggests(BreakpointSuggestionProvider.INSTANCE)
                                    .executes(context -> {
                                        final String key = StringArgumentType.getString(context, "key");
                                        var nbt = getNBT(key, context.getSource());
                                        if(nbt != null){
                                            if(nbt.getRight()){
                                                context.getSource().sendFeedback(() -> Text.translatable("datapack-debugger.commands.breakpoint.get", key, NbtHelper.toPrettyPrintedText(nbt.getLeft())), false);
                                            }else {
                                                context.getSource().sendError(Text.translatable("datapack-debugger.commands.breakpoint.get.fail.not_macro"));
                                            }
                                        }
                                        return 1;
                                    })
                            )
                            .executes(context -> {
                                final var args = getAllNBT(context.getSource());
                                if(args == null){
                                    context.getSource().sendError(Text.translatable("datapack-debugger.commands.breakpoint.get.fail.not_macro"));
                                }else {
                                    context.getSource().sendFeedback(() -> (NbtHelper.toPrettyPrintedText(args)), false);
                                }
                                return 1;
                            })
                    )
                    .then(literal("stack")
                            .executes(context -> {
                                MutableText text = Text.empty();
                                var stacks = ScopeManager.get().getDebugScopes();
                                for (var stack : stacks) {
                                    var t = Text.literal(stack.getFunction());
                                    var style = t.getStyle();
                                    if(stacks.indexOf(stack) == 0){
                                        style = style.withBold(true);
                                    }else {
                                        style = style.withBold(false);
                                    }
                                    t.setStyle(style);
                                    text = text.append(t);
                                    text.append("\n");
                                }
                                final MutableText finalText = text;
                                context.getSource().sendFeedback(() -> finalText, false);
                                return 1;
                            })
                    )
                    .then(literal("run")
                            .redirect(dispatcher.getRoot(), context -> (ServerCommandSource) ScopeManager.get().getCurrentScope().map(ScopeManager.DebugScope::getExecutor).orElse(null))
                    )
                    .then(literal("clear")
                            .executes(context -> {
                                clear();
                                return 1;
                            })
                    )
                    .then(literal("on")
                            .executes(context -> {
                                context.getSource().sendFeedback(() -> Text.translatable("datapack-debugger.commands.breakpoint.on"), false);
                                debugMode = true;
                                return 1;
                            })
                    )
                    .then(literal("off")
                            .executes(context -> {
                                context.getSource().sendFeedback(() -> Text.translatable("datapack-debugger.commands.breakpoint.off"), false);
                                debugMode = false;
                                return 1;
                            })
                    )
            );
        });
    }

    /**
     * Clears all debugging state and resets the system.
     * This includes clearing breakpoints, command contexts, and function stacks.
     */
    public static void clear(){
        isDebugCommand = false;
        isDebugging = false;
        debugMode = true;
        moveSteps = 0;
        isStepOver = false;
        stepOverDepth = -1;
        storedCommandExecutionContext.clear();
    }

    /**
     * Steps through the code execution for a specified number of steps.
     * @param steps Number of steps to execute
     * @param source The command source that triggered the step
     */
    public static void step(int steps, ServerCommandSource source) {
        if (!isDebugging) {
            source.sendError(Text.translatable("datapack-debugger.commands.breakpoint.step.fail"));
            return;
        }
        isDebugCommand = true;
        moveSteps = steps;
        CommandExecutionContext<?> context = null;
        try {
            while (moveSteps > 0) {
                context = storedCommandExecutionContext.peekFirst();
                if (context != null) {
                    var cls = context.getClass();
                    var method = cls.getDeclaredMethod("onStep");
                    method.setAccessible(true);
                    method.invoke(context);
                    if (moveSteps != 0) {
                        storedCommandExecutionContext.pollFirst().close();
                    }else {
                        var method1 = cls.getDeclaredMethod("ifContainsCommandAction");
                        method1.setAccessible(true);
                        boolean result = (boolean) method1.invoke(context);
                        if(!result){
                            storedCommandExecutionContext.pollFirst().close();
                        }
                        break;
                    }
                } else {
                    source.sendFeedback(() -> Text.translatable("datapack-debugger.commands.breakpoint.step.over"), false);
                    continueExec(source);
                }
            }
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
        } finally {
            isDebugCommand = false;
            if (context != null) {
                try {
                    context.close();
                } catch (Exception e) {
                    LOGGER.error(e.getMessage());
                }
            }
        }
    }

    /**
     * Continues execution from the current breakpoint.
     * @param source The command source that triggered the continue
     */
    public static void continueExec(@NotNull ServerCommandSource source) {
        if(!isDebugging){
            source.sendError(Text.translatable("datapack-debugger.commands.breakpoint.move.not_debugging"));
            return;
        }
        source.getServer().getTickManager().setFrozen(false);
        DebuggerState.get().continueExec();
        isDebugging = false;
        moveSteps = 0;
        for (CommandExecutionContext<?> context : storedCommandExecutionContext) {
            try {
                context.run();
                context.close();
            } catch (Exception e) {
                LOGGER.error(e.toString());
            }
        }
    }

    /**
     * Retrieves the NBT value for a given key from the current context.
     * @param key The key to look up
     * @param source The command source requesting the value
     * @return A pair containing the NBT element and whether it's a macro, or null if not found
     */
    private static @Nullable Pair<NbtElement, Boolean> getNBT(String key, ServerCommandSource source){
        var context = storedCommandExecutionContext.peekFirst();
        if(context == null){
            return null;
        }
        try {
            var cls = context.getClass();
            var method = cls.getDeclaredMethod("getKey", String.class);
            method.setAccessible(true);
            return (Pair<NbtElement, Boolean>) method.invoke(context, key);
        }catch (Exception e){
            LOGGER.error(e.toString());
            source.sendError(Text.translatable("datapack-debugger.commands.breakpoint.get.fail.error", e.toString()));
            return null;
        }
    }

    /**
     * Retrieves all NBT values from the current context.
     * @param source The command source requesting the values
     * @return The NBT element containing all values, or null if not available
     */
    private static @Nullable NbtElement getAllNBT(ServerCommandSource source){
        var context = storedCommandExecutionContext.peekFirst();
        if(context == null){
            return null;
        }
        try {
            var cls = context.getClass();
            var method = cls.getDeclaredMethod("getAllNBT");
            method.setAccessible(true);
            return (NbtElement) method.invoke(context);
        }catch (Exception e){
            LOGGER.error(e.toString());
            source.sendError(Text.translatable("datapack-debugger.commands.breakpoint.get.fail.error", e.toString()));
            return null;
        }
    }

//    /**
//     * Steps over the next command, executing it without entering function calls.
//     * @param steps Number of steps to execute
//     * @param source The command source that triggered the step over
//     */
//    public static void stepOver(int steps, ServerCommandSource source) {
//        if (!isDebugging) {
//            source.sendError(Text.translatable("commands.breakpoint.step.fail"));
//            return;
//        }
//        isDebugCommand = true;
//        moveSteps = steps;
//        CommandExecutionContext<?> context = null;
//        try {
//            while (moveSteps > 0) {
//                context = storedCommandExecutionContext.peekFirst();
//                if (context != null) {
//                    callFunction(context, "onStepOver");
//                    if (moveSteps != 0) {
//                        storedCommandExecutionContext.pollFirst().close();
//                    } else {
//                        boolean result = (boolean) callFunction(context, "ifContainsCommandAction");
//                        if(!result){
//                            storedCommandExecutionContext.pollFirst().close();
//                        }
//                        break;
//                    }
//                } else {
//                    source.sendFeedback(() -> Text.translatable("commands.breakpoint.step.over"), false);
//                    continueExec(source);
//                }
//            }
//        } catch (Exception e) {
//            LOGGER.error(e.getMessage());
//        } finally {
//            isDebugCommand = false;
//            if (context != null) {
//                try {
//                    context.close();
//                } catch (Exception e) {
//                    LOGGER.error(e.toString());
//                }
//            }
//        }
//    }
}
