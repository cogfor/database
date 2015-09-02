/* Generated By:JJTree: Do not edit this line. ASTOrderCondition.java */

package com.bigdata.rdf.sail.sparql.ast;

import com.bigdata.rdf.sail.sparql.ast.SimpleNode;
import com.bigdata.rdf.sail.sparql.ast.SyntaxTreeBuilder;
import com.bigdata.rdf.sail.sparql.ast.SyntaxTreeBuilderVisitor;
import com.bigdata.rdf.sail.sparql.ast.VisitorException;

public class ASTOrderCondition extends SimpleNode {

	private boolean ascending = true;

	public ASTOrderCondition(int id) {
		super(id);
	}

	public ASTOrderCondition(SyntaxTreeBuilder p, int id) {
		super(p, id);
	}

	@Override
	public Object jjtAccept(SyntaxTreeBuilderVisitor visitor, Object data)
		throws VisitorException
	{
		return visitor.visit(this, data);
	}

	public boolean isAscending() {
		return ascending;
	}

	public void setAscending(boolean ascending) {
		this.ascending = ascending;
	}

	@Override
	public String toString()
	{
		String result = super.toString();

		if (!ascending) {
			result += " (DESC)";
		}

		return result;
	}
}
