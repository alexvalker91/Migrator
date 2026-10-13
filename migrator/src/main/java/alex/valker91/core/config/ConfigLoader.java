package alex.valker91.core.config;

import org.aeonbits.owner.ConfigFactory;


public class ConfigLoader {

	public static WebdriverioConfig loadWebdriverioConfig() {
		return ConfigFactory.create(WebdriverioConfig.class, System.getProperties(), System.getenv());
	}
}