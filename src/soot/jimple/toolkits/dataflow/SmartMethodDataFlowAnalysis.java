package soot.jimple.toolkits.dataflow;

import soot.*;
import java.util.*;
import soot.toolkits.graph.*;
import soot.jimple.internal.*;
import soot.jimple.*;

// MethodDataFlowAnalysis written by Richard L. Halpert, 2007-02-25
// Constructs a data flow table for the given method.  Ignores indirect flow.
// These tables conservatively approximate how data flows from parameters,
// fields, and globals to parameters, fields, globals, and the return value.
// Note that a ref-type parameter (or field or global) might allow access to a
// large data structure, but that entire structure will be represented only by
// the parameter's one node in the data flow graph.

public class SmartMethodDataFlowAnalysis
{
	UnitGraph graph;
	SootMethod sm;
	Value thisLocal;
	DataFlowAnalysis dfa;
	boolean refOnly; // determines if primitive type data flow is included
	boolean includeInnerFields; // determines if flow to a field of an object (other than this) is treated like flow to that object
	
	MutableDirectedGraph abbreviatedDataFlowGraph;
	MutableDirectedGraph dataFlowSummary;
	Ref returnRef;
	
	boolean printMessages;
	
	public static int counter = 0;
	
	public SmartMethodDataFlowAnalysis(UnitGraph g, DataFlowAnalysis dfa)
	{
		graph = g;
		this.sm = g.getBody().getMethod();
		if(sm.isStatic())
			this.thisLocal = null;
		else
			this.thisLocal = g.getBody().getThisLocal();
		this.dfa = dfa;
		this.refOnly = !dfa.includesPrimitiveDataFlow();
		this.includeInnerFields = dfa.includesInnerFields();
		
		this.abbreviatedDataFlowGraph = new MemoryEfficientGraph();
		this.dataFlowSummary = new MemoryEfficientGraph();
		
		this.returnRef = new ParameterRef(g.getBody().getMethod().getReturnType(), -1); // it's a dummy parameter ref
		
//		this.entrySet = new ArraySparseSet();
//		this.emptySet = new ArraySparseSet();
		
		printMessages = false;
		
		counter++;
		
		// Add all of the nodes necessary to ensure that this is a complete data flow graph
		
		// Add every parameter of this method
		for(int i = 0; i < sm.getParameterCount(); i++)
		{
			EquivalentValue parameterRefEqVal = dfa.getEquivalentValueParameterRef(sm, i);
			if(!dataFlowSummary.containsNode(parameterRefEqVal))
				dataFlowSummary.addNode(parameterRefEqVal);
		}
		
		// Add every relevant field of this class (static methods don't get non-static fields)
		for(Iterator it = sm.getDeclaringClass().getFields().iterator(); it.hasNext(); )
		{
			SootField sf = (SootField) it.next();
			if(sf.isStatic() || !sm.isStatic())
			{
				EquivalentValue fieldRefEqVal = dfa.getEquivalentValueFieldRef(sm, sf);
				if(!dataFlowSummary.containsNode(fieldRefEqVal))
					dataFlowSummary.addNode(fieldRefEqVal);
			}
		}
		
		// Add every field of this class's superclasses
		SootClass superclass = sm.getDeclaringClass();
		if(superclass.hasSuperclass())
			superclass = sm.getDeclaringClass().getSuperclass();
		while(superclass.hasSuperclass()) // we don't want to process Object
		{
	        Iterator scFieldsIt = superclass.getFields().iterator();
	        while(scFieldsIt.hasNext())
	        {
				SootField scField = (SootField) scFieldsIt.next();
				if(scField.isStatic() || !sm.isStatic())
				{
					EquivalentValue fieldRefEqVal = dfa.getEquivalentValueFieldRef(sm, scField);
					if(!dataFlowSummary.containsNode(fieldRefEqVal))
						dataFlowSummary.addNode(fieldRefEqVal);
				}
	        }
			superclass = superclass.getSuperclass();
		}
		
		// Add thisref of this class
		if(!sm.isStatic())
		{
			EquivalentValue thisRefEqVal = dfa.getEquivalentValueThisRef(sm);
			if(!dataFlowSummary.containsNode(thisRefEqVal))
				dataFlowSummary.addNode(thisRefEqVal);
		}
		
		// Add returnref of this method
		EquivalentValue returnRefEqVal = new EquivalentValue(returnRef);
		if(returnRef.getType() != VoidType.v() && !dataFlowSummary.containsNode(returnRefEqVal))
			dataFlowSummary.addNode(returnRefEqVal);
		
		// Do the analysis
		Date start = new Date();
		int counterSoFar = counter;
		if(printMessages)
			G.v().out.println("STARTING SMART ANALYSIS FOR " + g.getBody().getMethod() + " -----");
		
		// S=#Statements, R=#Refs, L=#Locals, where generally (S ~= L), (L >> R)
		// Generates a data flow graph of refs and locals where "flows to data structure" is represented in a single node
		generateAbbreviatedDataFlowGraph(); // O(S)
		// Generates a data flow graph of refs where "flows to data structure" has been resolved
		generateDataFlowSummary(); // O( R*(L+R) )
		
		if(printMessages)
		{
	    	long longTime = ((new Date()).getTime() - start.getTime());
	    	float time = ((float) longTime) / 1000.0f;
			G.v().out.println("ENDING   SMART ANALYSIS FOR " + g.getBody().getMethod() + " ----- " + 
								(counter - counterSoFar + 1) + " analyses took: " + time + "s");
			G.v().out.println("  AbbreviatedDataFlowGraph:");
			DataFlowAnalysis.printDataFlowGraph(abbreviatedDataFlowGraph);
			G.v().out.println("  DataFlowSummary:");
			DataFlowAnalysis.printDataFlowGraph(dataFlowSummary);
		}
	}
	
	public void generateAbbreviatedDataFlowGraph()
	{
		Iterator stmtIt = graph.iterator();
		while(stmtIt.hasNext())
		{
			Stmt s = (Stmt) stmtIt.next();
			addFlowToCdfg(s);
		}
	}
	
	public void generateDataFlowSummary()
	{
		Iterator nodeIt = dataFlowSummary.iterator();
		while(nodeIt.hasNext())
		{
			EquivalentValue node = (EquivalentValue) nodeIt.next();
			List sources = sourcesOf(node);
			Iterator sourcesIt = sources.iterator();
			while(sourcesIt.hasNext())
			{
				EquivalentValue source = (EquivalentValue) sourcesIt.next();
				if(source.getValue() instanceof Ref)
				{
					dataFlowSummary.addEdge(source, node);
				}
			}
		}
	}
	
	public List sourcesOf(EquivalentValue node) { return sourcesOf(node, new HashSet(), new HashSet()); }
	private List sourcesOf(EquivalentValue node, Set visitedSources, Set visitedSinks)
	{
		visitedSources.add(node);
		
		List ret = new LinkedList();
		if(!abbreviatedDataFlowGraph.containsNode(node))
			return ret;

		// get direct sources
		List preds = abbreviatedDataFlowGraph.getPredsOf(node);
		Iterator predsIt = preds.iterator();
		while(predsIt.hasNext())
		{
			EquivalentValue pred = (EquivalentValue) predsIt.next();
			if(!visitedSources.contains(pred))
			{
				ret.add(pred);
				ret.addAll(sourcesOf(pred, visitedSources, visitedSinks));
			}
		}
		
		// get sources of (sources of sinks, of which we are one)
		List sinks = sinksOf(node, visitedSources, visitedSinks);
		Iterator sinksIt = sinks.iterator();
		while(sinksIt.hasNext())
		{
			EquivalentValue sink = (EquivalentValue) sinksIt.next();
			if(!visitedSources.contains(sink))
			{
				EquivalentValue flowsToSourcesOf = new EquivalentValue(new AbstractDataSource(sink.getValue()));
				
				if( abbreviatedDataFlowGraph.getPredsOf(sink).contains(flowsToSourcesOf) )
				{
					ret.addAll(sourcesOf(flowsToSourcesOf, visitedSources, visitedSinks));
				}
			}
		}
		return ret;
	}
	
	public List sinksOf(EquivalentValue node) { return sinksOf(node, new HashSet(), new HashSet()); }
	private List sinksOf(EquivalentValue node, Set visitedSources, Set visitedSinks)
	{
		List ret = new LinkedList();

//		if(visitedSinks.contains(node))
//			return ret;

		visitedSinks.add(node);
		
		if(!abbreviatedDataFlowGraph.containsNode(node))
			return ret;

		// get direct sinks
		List succs = abbreviatedDataFlowGraph.getSuccsOf(node);
		Iterator succsIt = succs.iterator();
		while(succsIt.hasNext())
		{
			EquivalentValue succ = (EquivalentValue) succsIt.next();
			if(!visitedSinks.contains(succ))
			{
				ret.add(succ);
				ret.addAll(sinksOf(succ, visitedSources, visitedSinks));
			}
		}
		
		// get sources of (sources of sinks, of which we are one)
		succsIt = succs.iterator();
		while(succsIt.hasNext())
		{
			EquivalentValue succ = (EquivalentValue) succsIt.next();
			if(succ.getValue() instanceof AbstractDataSource)
			{
				// It will have ONE successor, who will be the value whose sources it represents
				List vHolder = abbreviatedDataFlowGraph.getSuccsOf(succ);
				EquivalentValue v = (EquivalentValue) vHolder.get(0); // get the one and only
				if(!visitedSinks.contains(v))
				{
					ret.addAll(sourcesOf(v, visitedSinks, visitedSinks)); // these nodes are really to be marked as sinks, not sources
				}
			}
		}
		return ret;
	}
	
	public MutableDirectedGraph getMethodDataFlowSummary()
	{
		return dataFlowSummary;
	}

	protected boolean isNonRefType(Type type)
	{
		return !(type instanceof RefLikeType);
	}
	
	protected boolean ignoreThisDataType(Type type)
	{
		return refOnly && isNonRefType(type);
	}

	// For when data flows to a local
	protected void handleFlowsToValue(Value sink, Value source)
	{
		EquivalentValue sinkEqVal;
		EquivalentValue sourceEqVal;
		
		if(sink instanceof InstanceFieldRef)
			sinkEqVal = dfa.getEquivalentValueFieldRef(sm, ((FieldRef) sink).getField()); // deals with inner fields
		else
			sinkEqVal = new EquivalentValue(sink);
			
		if(source instanceof InstanceFieldRef)
			sourceEqVal = dfa.getEquivalentValueFieldRef(sm, ((FieldRef) source).getField()); // deals with inner fields
		else
			sourceEqVal = new EquivalentValue(source);
		
		if( source instanceof Ref && !dataFlowSummary.containsNode(sourceEqVal))
			dataFlowSummary.addNode(sourceEqVal);
		if( sink instanceof Ref && !dataFlowSummary.containsNode(sinkEqVal))
			dataFlowSummary.addNode(sinkEqVal);
		
		if(!abbreviatedDataFlowGraph.containsNode(sinkEqVal))
			abbreviatedDataFlowGraph.addNode(sinkEqVal);
		if(!abbreviatedDataFlowGraph.containsNode(sourceEqVal))
			abbreviatedDataFlowGraph.addNode(sourceEqVal);
		
		abbreviatedDataFlowGraph.addEdge(sourceEqVal, sinkEqVal);
	}
	
	// for when data flows to the data structure pointed to by a local
	protected void handleFlowsToDataStructure(Value base, Value source)
	{
		EquivalentValue sourcesOfBaseEqVal = new EquivalentValue(new AbstractDataSource(base));
		EquivalentValue baseEqVal = new EquivalentValue(base);

		EquivalentValue sourceEqVal;
		if(source instanceof InstanceFieldRef)
			sourceEqVal = dfa.getEquivalentValueFieldRef(sm, ((FieldRef) source).getField()); // deals with inner fields
		else
			sourceEqVal = new EquivalentValue(source);
		
		if( source instanceof Ref && !dataFlowSummary.containsNode(sourceEqVal))
			dataFlowSummary.addNode(sourceEqVal);
		
		if(!abbreviatedDataFlowGraph.containsNode(baseEqVal))
			abbreviatedDataFlowGraph.addNode(baseEqVal);
		if(!abbreviatedDataFlowGraph.containsNode(sourceEqVal))
			abbreviatedDataFlowGraph.addNode(sourceEqVal);
		if(!abbreviatedDataFlowGraph.containsNode(sourcesOfBaseEqVal))
			abbreviatedDataFlowGraph.addNode(sourcesOfBaseEqVal);

		abbreviatedDataFlowGraph.addEdge(sourceEqVal, sourcesOfBaseEqVal);
		abbreviatedDataFlowGraph.addEdge(sourcesOfBaseEqVal, baseEqVal); // for convenience
	}
	
	// handles the invoke expression AND returns a list of the return value's sources
		// for each node
			// if the node is a parameter
				// source = argument <Immediate>
			// if the node is a static field
				// source = node <StaticFieldRef>
			// if the node is a field
				// source = receiver object <Local>
			// if the node is the return value
				// continue
				
			// for each sink
				// if the sink is a parameter
					// handleFlowsToDataStructure(sink, source, fs)
				// if the sink is a static field
					// handleFlowsToValue(sink, source, fs)
				// if the sink is a field
					// handleFlowsToDataStructure(receiver object, source, fs)
				// if the sink is the return value
					// add node to list of return value sources

	protected List handleInvokeExpr(InvokeExpr ie)
	{
		// get the data flow graph
		MutableDirectedGraph dataFlowSummary = dfa.getInvokeDataFlowGraph(ie); // must return a graph whose nodes are Refs!!!
//		if( ie.getMethodRef().resolve().getSubSignature().equals(new String("boolean remove(java.lang.Object)")) )
//		{
//			G.v().out.println("*!*!*!*!*!<boolean remove(java.lang.Object)> has FLOW SENSITIVE dataFlowSummary: ");
//			ClassDataFlowAnalysis.printDataFlowGraph(dataFlowSummary);
//		}
		
		List returnValueSources = new ArrayList();
		
		Iterator nodeIt = dataFlowSummary.getNodes().iterator();
		while(nodeIt.hasNext())
		{
			EquivalentValue nodeEqVal = (EquivalentValue) nodeIt.next();
			
			if(!(nodeEqVal.getValue() instanceof Ref))
				throw new RuntimeException("Illegal node type in data flow summary:" + nodeEqVal.getValue() + " should be an object of type Ref.");
				
			Ref node = (Ref) nodeEqVal.getValue();
			
			Value source = null;
			
			if(node instanceof ParameterRef)
			{
				ParameterRef param = (ParameterRef) node;
				if(param.getIndex() == -1)
					continue;
				source = ie.getArg(param.getIndex()); // Immediate
			}
			else if(node instanceof StaticFieldRef)
			{
				source = node; // StaticFieldRef
			}
			else if(node instanceof InstanceFieldRef && ie instanceof InstanceInvokeExpr)
			{
				InstanceInvokeExpr iie = (InstanceInvokeExpr) ie;
				if(iie.getBase() == thisLocal || includeInnerFields)
					source = node;
				else
					source = iie.getBase(); // Local
			}
			else if(node instanceof InstanceFieldRef && includeInnerFields)
			{
				source = node;
			}
			else if(node instanceof ThisRef && ie instanceof InstanceInvokeExpr)
			{
				InstanceInvokeExpr iie = (InstanceInvokeExpr) ie;
				source = iie.getBase(); // Local
			}
			else
			{
				throw new RuntimeException("Unknown Node Type in Data Flow Graph: node " + node + " in InvokeExpr " + ie);
			}
			
			Iterator sinksIt = dataFlowSummary.getSuccsOf(nodeEqVal).iterator();
			while(sinksIt.hasNext())
			{
				EquivalentValue sinkEqVal = (EquivalentValue) sinksIt.next();
				Ref sink = (Ref) sinkEqVal.getValue();
				if(sink instanceof ParameterRef)
				{
					ParameterRef param = (ParameterRef) sink;
					if(param.getIndex() == -1)
					{
						returnValueSources.add(source);
					}
					else
					{
						handleFlowsToDataStructure(ie.getArg(param.getIndex()), source);
					}
				}
				else if(sink instanceof StaticFieldRef)
				{
					handleFlowsToValue(sink, source);
				}
				else if(sink instanceof InstanceFieldRef && ie instanceof InstanceInvokeExpr)
				{
					InstanceInvokeExpr iie = (InstanceInvokeExpr) ie;
					if(iie.getBase() == thisLocal || includeInnerFields)
						handleFlowsToValue(sink, source);
					else
						handleFlowsToDataStructure(iie.getBase(), source);
				}
				else if(sink instanceof InstanceFieldRef && includeInnerFields)
				{
					handleFlowsToValue(sink, source);
				}
			}
		}
				
		// return the list of return value sources
		return returnValueSources;
	}
	
	protected void addFlowToCdfg(Stmt stmt)
	{
		if(stmt instanceof IdentityStmt) // assigns an IdentityRef to a Local
		{
			IdentityStmt is = (IdentityStmt) stmt;
			IdentityRef ir = (IdentityRef) is.getRightOp();
			
			if(ir instanceof JCaughtExceptionRef)
			{
				// TODO: What the heck do we do with this???
			}
			else if(ir instanceof ParameterRef)
			{
				if( !ignoreThisDataType(ir.getType()) )
				{
					// <Local, ParameterRef and sources>
					handleFlowsToValue(is.getLeftOp(), ir);
				}
			}
			else if(ir instanceof ThisRef)
			{
				if( !ignoreThisDataType(ir.getType()) )
				{
					// <Local, ThisRef and sources>
					handleFlowsToValue(is.getLeftOp(), ir);
				}
			}
		}
		else if(stmt instanceof ReturnStmt) // assigns an Immediate to the "returnRef"
		{
			ReturnStmt rs = (ReturnStmt) stmt;
			Value rv = rs.getOp();
			if(rv instanceof Constant)
			{
				// No (interesting) data flow
			}
			else if(rv instanceof Local)
			{
				if( !ignoreThisDataType(rv.getType()) )
				{
					// <ReturnRef, sources of Local>
					handleFlowsToValue(returnRef, rv);
				}
			}
		}
		else if(stmt instanceof AssignStmt) // assigns a Value to a Variable
		{
			AssignStmt as = (AssignStmt) stmt;
			Value lv = as.getLeftOp();
			Value rv = as.getRightOp();
			
			Value sink = null;
			boolean flowsToDataStructure = false;
			
			if(lv instanceof Local) // data flows into the Local
			{
				sink = lv;
			}
			else if(lv instanceof ArrayRef) // data flows into the base's data structure
			{
				ArrayRef ar = (ArrayRef) lv;
				sink = ar.getBase();
				flowsToDataStructure = true;
			}
			else if(lv instanceof StaticFieldRef) // data flows into the field ref
			{
				sink = lv;
			}
			else if(lv instanceof InstanceFieldRef)
			{
				InstanceFieldRef ifr = (InstanceFieldRef) lv;
				if( ifr.getBase() == thisLocal || includeInnerFields ) // data flows into the field ref
				{
					sink = lv;
				}
				else // data flows into the base's data structure
				{
					sink = ifr.getBase();
					flowsToDataStructure = true;
				}
			}
			
			List sources = new ArrayList();
			boolean interestingFlow = true;
			
			if(rv instanceof Local)
			{
				sources.add(rv);
				interestingFlow = !ignoreThisDataType(rv.getType());
			}
			else if(rv instanceof Constant)
			{
				sources.add(rv);
				interestingFlow = !ignoreThisDataType(rv.getType());
			}
			else if(rv instanceof ArrayRef) // data flows from the base's data structure
			{
				ArrayRef ar = (ArrayRef) rv;
				sources.add(ar.getBase());
				interestingFlow = !ignoreThisDataType(ar.getType());
			}
			else if(rv instanceof StaticFieldRef)
			{
				sources.add(rv);
				interestingFlow = !ignoreThisDataType(rv.getType());
			}
			else if(rv instanceof InstanceFieldRef)
			{
				InstanceFieldRef ifr = (InstanceFieldRef) rv;
				if( ifr.getBase() == thisLocal || includeInnerFields ) // data flows from the field ref
				{
					sources.add(rv);
					interestingFlow = !ignoreThisDataType(rv.getType());
				}
				else // data flows from the base's data structure
				{
					sources.add(ifr.getBase());
					interestingFlow = !ignoreThisDataType(ifr.getType());
				}
			}
			else if(rv instanceof AnyNewExpr)
			{
				sources.add(rv);
				interestingFlow = !ignoreThisDataType(rv.getType());
			}
			else if(rv instanceof BinopExpr) // does this include compares and others??? yes
			{
				BinopExpr be = (BinopExpr) rv;
				sources.add(be.getOp1());
				sources.add(be.getOp2());
				interestingFlow = !ignoreThisDataType(be.getType());
			}
			else if(rv instanceof CastExpr)
			{
				CastExpr ce = (CastExpr) rv;
				sources.add(ce.getOp());
				interestingFlow = !ignoreThisDataType(ce.getType());
			}
			else if(rv instanceof InstanceOfExpr)
			{
				InstanceOfExpr ioe = (InstanceOfExpr) rv;
				sources.add(ioe.getOp());
				interestingFlow = !ignoreThisDataType(ioe.getType());
			}
			else if(rv instanceof UnopExpr)
			{
				UnopExpr ue = (UnopExpr) rv;
				sources.add(ue.getOp());
				interestingFlow = !ignoreThisDataType(ue.getType());
			}
			else if(rv instanceof InvokeExpr)
			{
				InvokeExpr ie = (InvokeExpr) rv;
				sources.addAll(handleInvokeExpr(ie));
				interestingFlow = !ignoreThisDataType(ie.getType());
			}
			
			if(interestingFlow)
			{
				if(flowsToDataStructure)
				{
					Iterator sourcesIt = sources.iterator();
					while(sourcesIt.hasNext())
					{
						Value source = (Value) sourcesIt.next();
						handleFlowsToDataStructure(sink, source);
					}
				}
				else
				{
					Iterator sourcesIt = sources.iterator();
					while(sourcesIt.hasNext())
					{
						Value source = (Value) sourcesIt.next();
						handleFlowsToValue(sink, source);
					}
				}
			}
		}
		else if(stmt.containsInvokeExpr()) // flows data between receiver object, parameters, globals, and return value
		{
			handleInvokeExpr(stmt.getInvokeExpr());
		}
	}
	
	public Value getThisLocal()
	{
		return thisLocal;
	}
}

