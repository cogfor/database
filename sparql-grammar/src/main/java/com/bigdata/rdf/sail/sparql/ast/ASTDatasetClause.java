/* Generated By:JJTree: Do not edit this line. ASTDatasetClause.java */

package com.bigdata.rdf.sail.sparql.ast;

import com.bigdata.rdf.sail.sparql.ast.SimpleNode;
import com.bigdata.rdf.sail.sparql.ast.SyntaxTreeBuilder;
import com.bigdata.rdf.sail.sparql.ast.SyntaxTreeBuilderVisitor;
import com.bigdata.rdf.sail.sparql.ast.VisitorException;

public class ASTDatasetClause extends SimpleNode {

    private boolean named;
    private boolean virtual;

	public ASTDatasetClause(int id) {
		super(id);
	}

	public ASTDatasetClause(SyntaxTreeBuilder p, int id) {
		super(p, id);
	}

	@Override
	public Object jjtAccept(SyntaxTreeBuilderVisitor visitor, Object data)
		throws VisitorException
	{
		return visitor.visit(this, data);
	}

    public boolean isNamed() {
        return named;
    }

    public void setNamed(boolean named) {
        this.named = named;
    }

    public boolean isVirtual() {
        return virtual;
    }

    public void setVirtual(boolean virtual) {
        this.virtual = virtual;
    }

	@Override
	public String toString()
	{
        return super.toString() + " (named=" + named + ", virtual=" + virtual
                + ")";
    }
}
