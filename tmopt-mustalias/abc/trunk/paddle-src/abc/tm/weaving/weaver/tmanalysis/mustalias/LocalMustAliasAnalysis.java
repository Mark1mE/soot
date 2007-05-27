package abc.tm.weaving.weaver.tmanalysis.mustalias;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Collection;
import java.util.LinkedList;

import soot.Local;
import soot.Value;
import soot.ValueBox;
import soot.RefLikeType;
import soot.jimple.NewExpr;
import soot.jimple.DefinitionStmt;
import soot.jimple.Stmt;
import soot.toolkits.graph.UnitGraph;
import soot.toolkits.scalar.ForwardFlowAnalysis;


public class LocalMustAliasAnalysis extends ForwardFlowAnalysis
{
    private Map objectMap = new HashMap();
	private static final Object UNKNOWN = new Object();
	private List<Local> locals;

    public LocalMustAliasAnalysis(UnitGraph g)
    {
        super(g);
        locals = new LinkedList<Local>(); locals.addAll(g.getBody().getLocals());

		for (Local l : (Collection<Local>) g.getBody().getLocals()) {
			if (l.getType() instanceof RefLikeType)
				locals.add(l);
        }

        doAnalysis();
    }

    protected void merge(Object in1, Object in2, Object o)
    {
        HashMap inMap1 = (HashMap) in1;
        HashMap inMap2 = (HashMap) in2;
        HashMap outMap = (HashMap) o;

        for (Local l : locals) {
            Set l1 = (Set)inMap1.get(l), l2 = (Set)inMap2.get(l);
            Set out = (Set)outMap.get(l);
            out.clear();
            if (l1.contains(UNKNOWN) || l2.contains(UNKNOWN)) {
                out.add(UNKNOWN);
            } else {
                out.addAll(l1); out.retainAll(l2);
            }
        }
    }
    

    protected void flowThrough(Object inValue, Object unit,
            Object outValue)
    {
        HashMap     in  = (HashMap) inValue;
        HashMap     out = (HashMap) outValue;
        Stmt    s   = (Stmt)    unit;

        out.clear();

        List<Local> preserve = new ArrayList();
        preserve.addAll(locals);
        for (ValueBox vb : (Collection<ValueBox>)s.getDefBoxes()) {
            preserve.remove(vb.getValue());
        }

        for (Local l : preserve) {
            out.put(l, in.get(l));
        }

        if (s instanceof DefinitionStmt) {
            DefinitionStmt ds = (DefinitionStmt) s;
            Value lhs = ds.getLeftOp();
            Value rhs = ds.getRightOp();
            if (lhs instanceof Local) {
                HashSet lv = new HashSet();
                out.put(lhs, lv);
                if (rhs instanceof NewExpr) {
                    lv.add(rhs);
                } else if (rhs instanceof Local) {
                    lv.addAll((HashSet)in.get(rhs));
                } else lv.add(UNKNOWN);
            }
        }
    }

    protected void copy(Object source, Object dest)
    {
        HashMap sourceMap = (HashMap) source;
        HashMap destMap   = (HashMap) dest;
            
		for (Local l : (Collection<Local>) locals) {
			destMap.put (l, sourceMap.get(l));
		}
    }

    protected Object entryInitialFlow()
    {
        HashMap m = new HashMap();
		for (Local l : (Collection<Local>) locals) {
			HashSet s = new HashSet(); s.add(UNKNOWN);
			m.put(l, s);
		}
        return m;
    }
        
    protected Object newInitialFlow()
    {
        HashMap m = new HashMap();
		for (Local l : (Collection<Local>) locals) {
			HashSet s = new HashSet(); 
			m.put(l, s);
		}
        return m;
    }

	/**
	 * @return true if values of l1 (at s1) and l2 (at s2) are known
	 * to have different creation sites
	 */
	public boolean mustAlias(Local l1, Stmt s1, Local l2, Stmt s2) {
		Set l1n = (Set) ((HashMap)getFlowBefore(s1)).get(l1);
		Set l2n = (Set) ((HashMap)getFlowBefore(s2)).get(l2);

        if (l1n.contains(UNKNOWN) || l2n.contains(UNKNOWN))
            return false;

		return l1n.containsAll(l2n) && l2n.containsAll(l1n);
	}
        
}
