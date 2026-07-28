package sap.capire.emoji_plugin;

import com.sap.cds.services.runtime.CdsRuntimeConfiguration;
import com.sap.cds.services.runtime.CdsRuntimeConfigurer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EmojiHandlerRuntimeConfiguration implements CdsRuntimeConfiguration {

	private static final Logger logger = LoggerFactory.getLogger(EmojiHandlerRuntimeConfiguration.class);

	@Override
	public void eventHandlers(CdsRuntimeConfigurer configurer) {
		
		// Check if remote service bindings exist in the environment
		// CdsRuntime runtime = configurer.getCdsRuntime();
		// boolean isRemoteServiceEnabled = runtime.getEnvironment().getCdsProperties().{YOUR_SERVICE};
		// if (isRemoteServiceEnabled && serviceBindingsExist()) {
		    // Use productive handler for real remote service
		// } else {
		    // Use mock handler for local development
		// }

		configurer.eventHandler(new EmojiHandler());
		logger.info("Registered EmojiHandler event handler.");
	}
	
}