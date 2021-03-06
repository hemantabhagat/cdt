/*******************************************************************************
 * Copyright (c) 2017 QNX Software Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.cdt.launch.serial.internal;

import org.eclipse.cdt.debug.core.launch.CoreBuildGenericLaunchConfigProvider;
import org.eclipse.cdt.launch.serial.SerialFlashLaunchTargetProvider;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.launchbar.core.ILaunchDescriptor;
import org.eclipse.launchbar.core.target.ILaunchTarget;

public class SerialFlashLaunchConfigProvider extends CoreBuildGenericLaunchConfigProvider {

	@Override
	public boolean supports(ILaunchDescriptor descriptor, ILaunchTarget target) throws CoreException {
		return target.getTypeId().equals(SerialFlashLaunchTargetProvider.TYPE_ID);
	}

	@Override
	public ILaunchConfigurationType getLaunchConfigurationType(ILaunchDescriptor descriptor, ILaunchTarget target)
			throws CoreException {
		return DebugPlugin.getDefault().getLaunchManager()
				.getLaunchConfigurationType(SerialFlashLaunchConfigDelegate.TYPE_ID);
	}

}
