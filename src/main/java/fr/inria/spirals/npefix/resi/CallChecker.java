package fr.inria.spirals.npefix.resi;

import fr.inria.spirals.npefix.resi.context.Decision;
import fr.inria.spirals.npefix.resi.context.Location;
import fr.inria.spirals.npefix.resi.context.MethodContext;
import fr.inria.spirals.npefix.resi.context.NPEFixExecution;
import fr.inria.spirals.npefix.resi.context.instance.Instance;
import fr.inria.spirals.npefix.resi.exception.ForceReturn;
import fr.inria.spirals.npefix.resi.selector.DomSelector;
import fr.inria.spirals.npefix.resi.selector.Selector;
import fr.inria.spirals.npefix.resi.strategies.AbstractStrategy;
import fr.inria.spirals.npefix.resi.strategies.NoStrat;
import fr.inria.spirals.npefix.resi.strategies.Strat4;
import fr.inria.spirals.npefix.resi.strategies.Strategy;

import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

@SuppressWarnings("all")
public class CallChecker {

	public static Selector strategySelector = new DomSelector();

	public static NPEFixExecution currentExecution = new NPEFixExecution(strategySelector);

	private static Stack<MethodContext> stack = new Stack<>();

	private static boolean isEnable = true;
	private static Strategy strategyBackup;
	private static Selector selectorBackup;

	public static Location currentLocation;
	public static ClassLoader currentClassLoader;

	public static void clear() {
		strategyBackup = null;
		selectorBackup = null;
		stack = new Stack<>();
		currentExecution = new NPEFixExecution(strategySelector);
		isEnable = true;
		decisions.clear();
		cache.clear();
	}

	public static <T> Decision<T> getDecision(List<Decision<T>> decisions) {
		return strategySelector.select(decisions);
	}

	private static <T> List<Decision<T>> getSearchSpace(Strategy.ACTION action, Class clazz, Location location) {
		List<Decision<T>> output = new ArrayList<>();
		List<Strategy> strategies = strategySelector.getStrategies();
		disable();
		for (int i = 0; i < strategies.size(); i++) {
			Strategy strategy = strategies.get(i);
			try {
				output.addAll(strategy.getSearchSpace(clazz, location));
			} catch (Exception e) {
				continue;
			}
		}
		enable();
		return output;
	}

	public static  Map<Location, List<Decision>> cache = new HashMap<>();
	public static  Map<Location, Decision> decisions = new HashMap<>();

	private static <T> T called(Strategy.ACTION action, T o, Class clazz, int line, int sourceStart, int sourceEnd) {
		if(o != null
				|| ExceptionStack.isStoppable(NullPointerException.class)) {
			return action == Strategy.ACTION.beforeDeref? (T) Boolean.TRUE : o;
		}

		Location location = getLocation(line, sourceStart, sourceEnd);

		if(decisions.containsKey(location)) {
			if(!decisions.get(location).getStrategy().isCompatibleAction(action)) {
				return o;
			}
			if(decisions.get(location).getStrategy() instanceof Strat4) {
				throw new ForceReturn(decisions.get(location));
			}

			return (T) decisions.get(location).getValue();
		}
		if(!isEnable()) {
			return o;
		}

		List<Decision<T>> searchSpace = new ArrayList<>();
		if(cache.containsKey(location)) {
			List<Decision> decisions = cache.get(location);
			for (int i = 0; i < decisions.size(); i++) {
				Decision<T> decision =  decisions.get(i);
				searchSpace.add(decision);
			}
		} else {
			searchSpace = getSearchSpace(action, clazz, location);
			cache.put(location, new ArrayList<Decision>(searchSpace));
		}
		System.out.println();

		if(searchSpace.isEmpty()) {
			return o;
		}

		Decision<?> decision = getDecision(searchSpace);
		decision.setUsed(true);
		//disable();

		currentExecution.addDecision(decision);

		decisions.put(location, decision);

		if(!decision.getStrategy().isCompatibleAction(action)) {
			return o;
		}

		if(decision.getStrategy() instanceof Strat4) {
			throw new ForceReturn(decision);
		}

		return (T) decision.getValue();
	}

	public static <T> T beforeCalled(T o, Class clazz, int line, int sourceStart, int sourceEnd) {
		return called(Strategy.ACTION.beforeCalled, o, clazz, line, sourceStart, sourceEnd);
	}

	public static <T> T isCalled(T o, Class clazz, int line, int sourceStart, int sourceEnd) {
		return called(Strategy.ACTION.isCalled, o, clazz, line, sourceStart, sourceEnd);
	}

	public static boolean beforeDeref(Object o, Class clazz, int line, int sourceStart, int sourceEnd) {
		Object called = called(Strategy.ACTION.beforeDeref, o, clazz, line,
				sourceStart, sourceEnd);
		if(called == null) {
			return true;
		}
		if(called instanceof Boolean) {
			return called.equals(Boolean.TRUE);
		}
		return true;
	}

	public static <T> T init(Class clazz) {
		if(!isEnable()) {
			if(clazz.isPrimitive()) {
				List<Instance<T>> primitive = AbstractStrategy.<T>initPrimitive(clazz);
				return (T) primitive.get(0).getValue();
			}
			return null;
		}
		disable();
		try {
			return (T) AbstractStrategy.<T>initClass(clazz).getValue();
		} finally {
			enable();
		}
	}

	/*public static <T> T returned(Class clazz) {
		Strategy strat = currentExecution.getMainDecision();
		if(strat == null) {
			strat = getStrat();
			currentExecution.setMainDecision(strat);
		}
		disable();
		try {
			return (T) strat.returned(clazz);
		} catch (Throwable e) {
			throw e;
		} finally {
			enable();
		}
	}*/

	public static <T> T varAssign(Object table, String variableName, int line, int sourceStart, int sourceEnd) {
		addObjectInStack(variableName, table);
		return (T) table;
	}

	public static <T> T varInit(Object table, String variableName, int line, int sourceStart, int sourceEnd) {
		addObjectInStack(variableName, table);
		return (T) table;
	}

	public static void enable() {
		if(isEnable()) {
			return;
		}
		CallChecker.strategySelector = selectorBackup;
		DomSelector.strategy = strategyBackup;
		isEnable = true;
	}

	public static void disable() {
		if(!isEnable()) {
			return;
		}
		selectorBackup = CallChecker.strategySelector;
		strategyBackup = DomSelector.strategy;
		CallChecker.strategySelector = new DomSelector();
		DomSelector.strategy = new NoStrat();
		isEnable = false;
	}

	public static boolean isEnable() {
		return isEnable;
	}

	private static void addObjectInStack(String variableName, Object table) {
		if (isEnable()
				&& !(CallChecker.strategySelector instanceof DomSelector
				&& DomSelector.strategy instanceof NoStrat) && !stack.isEmpty()) {
			disable();
			try {
				stack.peek().addVariable(variableName, table);
			} catch (Throwable e) {
				// ignore
			} finally {
				enable();
			}
		}
	}

	public static void methodStart(MethodContext methodType) {
		stack.push(methodType);
	}

	public static void methodEnd(MethodContext methodContext) {
		stack.remove(methodContext);
	}

	public static MethodContext getCurrentMethodContext() {
		return stack.peek();
	}

	public static Stack<MethodContext> getStack() {
		return stack;
	}

	private static Location getLocation(int line, int sourceStart, int sourceEnd) {
		Thread thread = Thread.currentThread();
		StackTraceElement[] stackTraces = thread.getStackTrace();
		StackTraceElement stackTrace = stackTraces[4];
		return new Location(stackTrace.getClassName(), line, sourceStart, sourceEnd);
	}

	public static Map<Location, Decision> getDecisions() {
		return decisions;
	}
}