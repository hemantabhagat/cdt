/*******************************************************************************
 * Copyright (c) 2004, 2007 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * IBM - Initial API and implementation
 *******************************************************************************/
package org.eclipse.cdt.internal.core.dom.parser.cpp;

import org.eclipse.cdt.core.dom.ast.ASTVisitor;
import org.eclipse.cdt.core.dom.ast.IASTDeclaration;
import org.eclipse.cdt.core.dom.ast.IASTDeclarator;
import org.eclipse.cdt.core.dom.ast.IASTName;
import org.eclipse.cdt.core.dom.ast.IASTNode;
import org.eclipse.cdt.core.dom.ast.IASTSimpleDeclaration;
import org.eclipse.cdt.core.dom.ast.IBinding;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPASTElaboratedTypeSpecifier;
import org.eclipse.cdt.core.parser.util.ArrayUtil;

/**
 * @author jcamelon
 */
public class CPPASTElaboratedTypeSpecifier extends CPPASTBaseDeclSpecifier
        implements ICPPASTElaboratedTypeSpecifier {

    private int kind;
    private IASTName name;

    
    public CPPASTElaboratedTypeSpecifier() {
	}

	public CPPASTElaboratedTypeSpecifier(int kind, IASTName name) {
		this.kind = kind;
		setName(name);
	}

	public int getKind() {
        return kind;
    }

    public void setKind(int value) {
        this.kind = value;
    }

    public IASTName getName() {
        return name;
    }

    public void setName(IASTName name) {
        this.name = name;
        if (name != null) {
			name.setParent(this);
			name.setPropertyInParent(TYPE_NAME);
		}
    }

    @Override
	public boolean accept( ASTVisitor action ){
        if( action.shouldVisitDeclSpecifiers ){
		    switch( action.visit( this ) ){
	            case ASTVisitor.PROCESS_ABORT : return false;
	            case ASTVisitor.PROCESS_SKIP  : return true;
	            default : break;
	        }
		}
        if( name != null ) if( !name.accept( action ) ) return false;
        if( action.shouldVisitDeclSpecifiers ){
		    switch( action.leave( this ) ){
	            case ASTVisitor.PROCESS_ABORT : return false;
	            case ASTVisitor.PROCESS_SKIP  : return true;
	            default : break;
	        }
		}
        return true;
    }

	public int getRoleForName(IASTName n) {
		if( n != name ) return r_unclear;
		
		IASTNode parent = getParent();
		if( !( parent instanceof IASTDeclaration ) )
			return r_reference;
		
		if( parent instanceof IASTSimpleDeclaration ){
			IASTDeclarator [] dtors = ((IASTSimpleDeclaration)parent).getDeclarators(); 
			if( dtors.length == 0 )
				return r_declaration;
		}
		
		//can't tell, resolve the binding
		IBinding binding = name.resolveBinding();
		if( binding instanceof ICPPInternalBinding ){
			IASTNode [] decls = ((ICPPInternalBinding)binding).getDeclarations();
			if( ArrayUtil.contains( decls, name ) ) 
				return r_declaration;
		}
		return r_reference;
	}
}
