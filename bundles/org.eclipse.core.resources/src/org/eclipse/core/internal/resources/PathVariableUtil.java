/*******************************************************************************
 * Copyright (c) 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Serge Beauchamp (Freescale Semiconductor) - initial API and implementation
 *******************************************************************************/
package org.eclipse.core.internal.resources;

import org.eclipse.core.runtime.Path;

import org.eclipse.core.internal.resources.projectVariables.ParentVariableProvider;

import org.eclipse.core.internal.resources.projectVariables.ProjectLocationProjectVariable;

import org.eclipse.core.runtime.IPath;

import org.eclipse.core.internal.resources.projectVariables.WorkspaceLocationProjectVariable;
import org.eclipse.core.resources.IPathVariableManager;
import org.eclipse.core.runtime.*;

public class PathVariableUtil {
	
	static public String getUniqueVariableName(String variable, IPathVariableManager pathVariableManager) {
		int index = 1;
		variable = getValidVariableName(variable);
		String destVariable = variable;

		while (pathVariableManager.isDefined(destVariable)) {
			destVariable = variable + index;
			index++;
		}
		return destVariable;
	}

	public static String getValidVariableName(String variable) {
		// remove the argument part if the variable is of the form ${VAR-ARG}
		int argumentIndex = variable.indexOf('-');
		if (argumentIndex != -1)
			variable = variable.substring(0, argumentIndex);
		
		variable = variable.trim();
		char first = variable.charAt(0);
		if (!Character.isLetter(first) && first != '_') {
			variable = 'A' + variable;
		}

		StringBuffer builder = new StringBuffer();
		for (int i = 0; i < variable.length(); i++) {
			char c = variable.charAt(i);
			if ((Character.isLetter(c) || Character.isDigit(c) || c == '_') &&
					!Character.isWhitespace(c))
				builder.append(c);
		}
		variable = builder.toString();
		return variable;
	}

	public static IPath convertToPathRelativeMacro(IPathVariableManager pathVariableManager, IPath originalPath, boolean force, String variableHint) throws CoreException {
		return convertToRelative(pathVariableManager, originalPath, force, variableHint, true, true);
	}

	static public IPath convertToRelative(IPathVariableManager pathVariableManager, IPath originalPath, boolean force, String variableHint) throws CoreException {
		return convertToRelative(pathVariableManager, originalPath, force, variableHint, true, false);
	}

	static private IPath convertToRelative(IPathVariableManager pathVariableManager, IPath originalPath, boolean force, String variableHint, boolean skipWorkspace, boolean generateMacro) throws CoreException {
		if (variableHint != null && pathVariableManager.isDefined(variableHint))
			return wrapInProperFormat(makeRelativeToVariable(pathVariableManager, originalPath, force, variableHint, generateMacro), generateMacro);
		IPath path = convertToProperCase(originalPath);
		IPath newPath = null;
		int maxMatchLength = -1;
		String[] existingVariables = pathVariableManager.getPathVariableNames();
		for (int i = 0; i < existingVariables.length; i++) {
			String variable = existingVariables[i];
			if (skipWorkspace) {
				// Variables relative to the workspace are not portable, and defeat the purpose of having linked resource locations, 
				// so they should not automatically be created relative to the workspace.
				if (variable.equals(WorkspaceLocationProjectVariable.NAME))
					continue; 
			}
			if (variable.equals(ParentVariableProvider.NAME))
				continue;
			// find closest path to the original path
			IPath value = pathVariableManager.getValue(variable);
			value = convertToProperCase(pathVariableManager.resolvePath(value));
			if (value.isPrefixOf(path)) {
				int matchLength = value.segmentCount();
				if (matchLength > maxMatchLength) {
					maxMatchLength = matchLength;
					newPath = makeRelativeToVariable(pathVariableManager, originalPath, force, variable, generateMacro);
				}
			}
		}
		if (newPath != null)
			return wrapInProperFormat(newPath, generateMacro);

		if (force) {
			int originalSegmentCount = originalPath.segmentCount();
			for (int j = 0; j <= originalSegmentCount; j++) {
				IPath matchingPath = path.removeLastSegments(j);
				int minDifference = Integer.MAX_VALUE;
				for (int k = 0; k < existingVariables.length; k++) {
					String variable = existingVariables[k];
					if (skipWorkspace) {
						if (variable.equals(WorkspaceLocationProjectVariable.NAME))
							continue;
					}
					if (variable.equals(ParentVariableProvider.NAME))
						continue;
					IPath value = pathVariableManager.getValue(variable);
					value = convertToProperCase(pathVariableManager.resolvePath(value));
					if (matchingPath.isPrefixOf(value)) {
						int difference = value.segmentCount() - originalSegmentCount;
						if (difference < minDifference) {
							minDifference = difference;
							newPath = makeRelativeToVariable(pathVariableManager, originalPath, force, variable, generateMacro);
						}
					}
				}
				if (newPath != null)
					return wrapInProperFormat(newPath, generateMacro);
			}
			if (originalSegmentCount == 0) {
				String variable = ProjectLocationProjectVariable.NAME;
				IPath value = pathVariableManager.getValue(variable);
				value = convertToProperCase(pathVariableManager.resolvePath(value));
				if (originalPath.isPrefixOf(value))
					newPath = makeRelativeToVariable(pathVariableManager, originalPath, force, variable, generateMacro);
				if (newPath != null)
					return wrapInProperFormat(newPath, generateMacro);
			}
		}

		if (skipWorkspace)
			return convertToRelative(pathVariableManager, originalPath, force, variableHint, false, generateMacro);
		return originalPath;
	}

	private static IPath wrapInProperFormat(IPath newPath, boolean generateMacro) {
		if (generateMacro)
			newPath = PathVariableUtil.buildVariableMacro(newPath);
		return newPath;
	}

	private static IPath makeRelativeToVariable(IPathVariableManager pathVariableManager, IPath originalPath, boolean force, String variableHint, boolean generateMacro) throws CoreException {
		IPath path = convertToProperCase(originalPath);
		IPath value = pathVariableManager.getValue(variableHint);
		value = convertToProperCase(pathVariableManager.resolvePath(value));
		int valueSegmentCount = value.segmentCount();
		if (value.isPrefixOf(path)) {
			// transform "c:/foo/bar" into "FOO/bar"
			IPath tmp = Path.fromOSString(variableHint);
			for (int j = valueSegmentCount;j < originalPath.segmentCount(); j++) {
				tmp = tmp.append(originalPath.segment(j));
			}
			return tmp;
		} 

		if (force) {
			// transform "c:/foo/bar/other_child/file.txt" into "${PARENT-1-BAR_CHILD}/other_child/file.txt"
			int matchingFirstSegments = path.matchingFirstSegments(value);
			if (matchingFirstSegments >= 0) {
				String newValue = buildParentPathVariable(variableHint, valueSegmentCount - matchingFirstSegments, generateMacro);
				String originalName;
				if (generateMacro) 
					originalName = newValue;
				else {
					originalName = getExistingVariable(newValue, pathVariableManager);
					if (originalName == null) {
						String name;
						if (matchingFirstSegments > 0)
							name = originalPath.segment(matchingFirstSegments - 1);
						else
							name = originalPath.getDevice();
						if (name == null)
							name = "ROOT"; //$NON-NLS-1$
						originalName = getUniqueVariableName(name, pathVariableManager);
						pathVariableManager.setValue(originalName, Path.fromOSString(newValue));
					}
				}
				IPath tmp = Path.fromOSString(originalName);
				for (int j = matchingFirstSegments ;j < originalPath.segmentCount(); j++) {
					tmp = tmp.append(originalPath.segment(j));
				}
				return tmp;
			}
		}
		return originalPath;
	}

	private static String getExistingVariable(String newValue, IPathVariableManager pathVariableManager) {
		String[] existingVariables = pathVariableManager.getPathVariableNames();
		for (int i = 0; i < existingVariables.length; i++) {
			String variable = existingVariables[i];
			IPath value = pathVariableManager.getValue(variable);
			if (value.toOSString().equals(newValue))
				return variable;
		}
		return null;
	}
	
	static private IPath convertToProperCase(IPath path) {
		if (Platform.getOS().equals(Platform.OS_WIN32))
			return Path.fromPortableString(path.toPortableString().toLowerCase());
		return path;
	}

	static public boolean isParentVariable(String variableString) {
		return variableString.startsWith(ParentVariableProvider.NAME + '-');
	}
	
	// the format is PARENT-COUNT-ARGUMENT
	static public int getParentVariableCount(String variableString) {
		String items[] = variableString.split("-"); //$NON-NLS-1$
		if (items.length == 3) {
			try {
				Integer count = Integer.valueOf(items[1]);
				return count.intValue();
			} catch (NumberFormatException e) {
				// nothing
			}
		}
		return -1;
	}
	
	// the format is PARENT-COUNT-ARGUMENT
	static public String getParentVariableArgument(String variableString) {
		String items[] = variableString.split("-"); //$NON-NLS-1$
		if (items.length == 3) 
			return items[2];
		return null;
	}

	static public String buildParentPathVariable(String variable, int difference, boolean generateMacro) {
		String 	newString = "PARENT-" + difference + "-" + variable;    //$NON-NLS-1$//$NON-NLS-2$

		if (!generateMacro)
			newString = "${" + newString + "}";    //$NON-NLS-1$//$NON-NLS-2$
		return newString;
	}

	public static IPath buildVariableMacro(IPath relativeSrcValue) {
		String variable = relativeSrcValue.segment(0);
		variable = "${" + variable + "}";  //$NON-NLS-1$//$NON-NLS-2$
		return Path.fromOSString(variable).append(relativeSrcValue.removeFirstSegments(1));
	}
}
