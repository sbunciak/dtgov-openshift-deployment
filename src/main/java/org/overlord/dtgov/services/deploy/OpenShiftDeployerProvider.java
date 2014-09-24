package org.overlord.dtgov.services.deploy;

import org.overlord.dtgov.common.Target;
import org.overlord.dtgov.services.deploy.deployers.OpenShiftDeployer;

import java.util.HashMap;
import java.util.Map;

/**
 * 
 * @author sbunciak
 *
 */
public class OpenShiftDeployerProvider implements DeployerProvider {

	@Override
	public Map<String, Deployer<? extends Target>> createDeployers() {
		Map<String, Deployer<? extends Target>> deployers = new HashMap<String, Deployer<? extends Target>>();
		deployers.put("openshift", new OpenShiftDeployer());
		return deployers;
	}

}
