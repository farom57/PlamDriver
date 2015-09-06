/**
 * 
 */
package farom.plamdriver;


import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import org.indilib.i4j.Constants.PropertyStates;
import org.indilib.i4j.INDIException;
import org.indilib.i4j.driver.INDIDriver;
import org.indilib.i4j.driver.INDINumberElementAndValue;
import org.indilib.i4j.driver.INDINumberProperty;
import org.indilib.i4j.driver.annotation.InjectElement;
import org.indilib.i4j.driver.annotation.InjectProperty;
import org.indilib.i4j.driver.ccd.Capability;
import org.indilib.i4j.driver.ccd.CcdFrame;
import org.indilib.i4j.driver.ccd.INDICCDDriver;
import org.indilib.i4j.driver.ccd.INDICCDImage;
import org.indilib.i4j.driver.ccd.INDICCDImage.ImageType;
import org.indilib.i4j.driver.ccd.INDICCDImage.PixelIterator;

import org.indilib.i4j.driver.connection.INDIConnectionHandler;
import org.indilib.i4j.driver.event.NumberEvent;
import org.indilib.i4j.protocol.api.INDIConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.usb4java.Context;
import org.usb4java.Device;
import org.usb4java.DeviceDescriptor;
import org.usb4java.DeviceHandle;
import org.usb4java.DeviceList;
import org.usb4java.LibUsb;
import org.usb4java.LibUsbException;
import org.usb4java.Transfer;
import org.usb4java.TransferCallback;


import nom.tam.fits.BasicHDU;


/**
 * @author farom
 *
 */
public class INDIPlamDriver extends INDICCDDriver implements INDIConnectionHandler, TransferCallback  {

	
    /**
     * The logger to log the messages to.
     */
    private static final Logger LOG = LoggerFactory.getLogger(INDIPlamDriver.class);
    
	private static final int BITS_PER_PIXEL_COLOR = 12;
	private static final int SIZE_X = 480;
	private static final int SIZE_Y = 640;
	private static final float PIXEL_SIZE = 5.6f;
	
	private static final short VENDOR_ID = 0x0547;
	private static final short PRODUCT_ID = 0x3303;
	private static final long CTRL_TIMEOUT = 100;
	private static final long IMG_TIMEOUT = 1000;
	private static final int IMG_BYTE_SIZE = SIZE_X * SIZE_Y * 2;
	private static final int IMG_PACKET_MAXSIZE = IMG_BYTE_SIZE;
	private static final byte BULK_ENDPOINT = (byte) 0x82;
	private static final double DURATION_COEF = 5923;
	private static final short IMGCTRL_GAIN = 0;
	private static final short IMGCTRL_BLACK = 1;
	private static final short IMGCTRL_THIGH = 6;
	private static final short IMGCTRL_TLOW = 7;
	

    /*@InjectProperty(name = "CCD_GAIN", label = "Gain", group = INDIDriver.GROUP_MAIN_CONTROL)
    private INDINumberProperty gainP;
    
    @InjectElement(name = "CCD_GAIN", label = "Level", numberValue = 750, maximum = 1023, minimum = 0, numberFormat = "%4.0f")
    private INDINumberProperty gainE;
    
    @InjectProperty(name = "CCD_BLACK", label = "Black", group = INDIDriver.GROUP_MAIN_CONTROL)
    private INDINumberProperty blackP;
    
    @InjectElement(name = "CCD_BLACK", label = "Level", numberValue = 0, maximum = 255, minimum = 0, numberFormat = "%3.0f")
    private INDINumberProperty blackE;*/
	
	private Device device;
	private DeviceHandle handle;
	private EventHandlingThread thread;
	private Context context;
	
	private byte raw[];

	private boolean closed = false;
	private boolean connected = false;
	private boolean captureOngoing;


	/**
	 * Default constructor
	 * @param connection the indi connection to the server
	 */
	public INDIPlamDriver(INDIConnection connection) {
		super(connection);
		
		// CCD Extension (set of standard properties for the CCD)
		try{
			primaryCCD.setCCDParams(SIZE_X, SIZE_Y, BITS_PER_PIXEL_COLOR, PIXEL_SIZE, PIXEL_SIZE);
		}catch(Exception e){
			e.printStackTrace();
			LOG.error("comme prévu",e);
		}
//		gainP.setEventHandler(new NumberEvent() {
//            @Override
//            public void processNewValue(Date date, INDINumberElementAndValue[] elementsAndValues) {
//                try{
//                	setGain(elementsAndValues[0].getValue().intValue());
//                }catch(IllegalArgumentException e){
//                	gainP.setState(PropertyStates.ALERT);
//                	updateProperty(gainP, e.getMessage());
//                }catch(LibUsbException e){
//                	gainP.setState(PropertyStates.ALERT);
//                	updateProperty(gainP, e.getMessage());
//                }
//                gainE.setValues(elementsAndValues);
//                gainP.setState(PropertyStates.OK);
//                updateProperty(gainP);
//            }
//        });
//		blackP.setEventHandler(new NumberEvent() {
//            @Override
//            public void processNewValue(Date date, INDINumberElementAndValue[] elementsAndValues) {
//                setBlack(elementsAndValues[0].getValue().intValue());
//            }
//        });
		
		
		// starting libusb and the associated thread
		context = new Context();
		int result = LibUsb.init(context);
		//LibUsb.setDebug(context,LibUsb.LOG_LEVEL_DEBUG);
		thread = new EventHandlingThread();
		thread.start();
		if (result != LibUsb.SUCCESS) {
			LOG.info("Unable to initialize libusb: " + result);
			return;
		}

	}

    /**
     * Abort the current exposure.
     * 
     * @return true is successful
     */
	@Override
	public boolean abortExposure() {
		// TODO Auto-generated method stub
		return false;
	}

    /**
     * Start exposing primary CCD chip. This function must be implemented in the
     * child class
     * 
     * @param duration
     *            Duration in seconds
     * @return true if OK and exposure will take some time to complete, false on
     *         error.
     */
	@Override
	public boolean startExposure(double duration) {
		if(!ready()){
			return false;
		}
		
		raw = new byte[IMG_BYTE_SIZE];

		requestTransfer(IMG_PACKET_MAXSIZE, IMG_TIMEOUT);	
		captureOngoing = true;

		
		if(duration>0){
			// Sending exposure duration;
			int time_int = (int) (duration * DURATION_COEF);
			short time_high = (short) (time_int / 4096);
			short time_low = (short) (time_int - time_high * 4096);
			sendImgCtrl(IMGCTRL_THIGH, time_high);
			sendImgCtrl(IMGCTRL_TLOW, time_low);
		}
				
		
		Timer timer = new Timer();
		timer.schedule(new TimerTask() {			
			@Override
			public void run() {
				requestTransfer(IMG_PACKET_MAXSIZE, IMG_TIMEOUT);				
			}
		}, Math.max(0,(long) (duration * 1000 - 10)));

		
		LOG.info("Exposure started");

		return true;
		
		
		
		
		
		
	}

	/**
	 * return true if it is ready to capture
	 * @return
	 */
	public boolean ready(){
		return !captureOngoing && connected;
	}
	
    /**
     * INDICCD calls this function when CCD Binning needs to be updated in the
     * hardware. Derived classes should implement this function
     * 
     * @param binX
     *            Horizontal binning.
     * @param binY
     *            Vertical binning.
     * @return true is CCD chip update is successful, false otherwise.
     */
	@Override
	public boolean updateCCDBin(int binX, int binY) {
		return false;
	}

    /**
     * INDICCD calls this function when CCD Frame dimension needs to be updated
     * in the hardware. Derived classes should implement this function
     * 
     * @param x
     *            Subframe X coordinate in pixels.
     * @param y
     *            Subframe Y coordinate in pixels.
     * @param width
     *            Subframe width in pixels.
     * @param height
     *            Subframe height in pixels. \note (0,0) is defined as most
     *            left, top pixel in the subframe.
     * @return true is CCD chip update is successful, false otherwise.
     */
	@Override
	public boolean updateCCDFrame(int x, int y, int width, int height) {
		return false;
	}

    /**
     * INDICCD calls this function when CCD frame type needs to be updated in
     * the hardware.The CCD hardware layer may either set the frame type when
     * this function is called, or (optionally) before an exposure is started.
     * 
     * @param frameType
     *            Frame type
     * @return true is CCD chip update is successful, false otherwise.
     */
	@Override
	public boolean updateCCDFrameType(CcdFrame frameType) {
		return false;
	}

    /**
     * get a map of any additinal fits header information to the fits image. if
     * no extra atts needed keep it null.
     * 
     * @param fitsHeader
     *            the orignal header with the existing attributes.
     * @return null or a map with the new header attributes.
     */
	@Override
	public Map<String, Object> getExtraFITSKeywords(BasicHDU fitsHeader) {
		// TODO Auto-generated method stub
		return null;
	}

    /**
     * @return a new Capability object that defines the capabilities of this ccd
     *         driver.
     */
	@Override
	protected Capability defineCapabilities() {
        Capability capabilities = new Capability();
        capabilities.canAbort(false);
        capabilities.canBin(false);
        capabilities.canSubFrame(false);
        capabilities.hasCooler(false);
        capabilities.hasGuideHead(false);
        capabilities.hasShutter(true);
        return capabilities;
	}

    /**
     * Set CCD temperature. Upon returning false, the property becomes BUSY.
     * Once the temperature reaches the requested value, change the state to OK.
     * This must be implemented in the child class
     * 
     * @param theTargetTemperature
     *            CCD temperature in degrees celcius.
     * @return true or false if setting the temperature call to the hardware is
     *         successful. null if an error is encountered. Return false if
     *         setting the temperature to the requested value takes time. Return
     *         true if setting the temperature to the requested value is
     *         complete.
     */
	@Override
	protected Boolean setTemperature(double theTargetTemperature) {
		return null;
	}

    /**
     * Gets the name of the Driver.
     * 
     * @return The name of the Driver.
     */
	@Override
	public String getName() {
		return "iNova PLa-M camera";
	}
	
    @Override
    public void driverConnect(Date timestamp) throws INDIException {
    	LibUsbException e=null;
    	try{
        	connect();
        }catch(LibUsbException tmp){
        	e=tmp;
        }
        
        if(!connected){
        	throw new INDIException("Unable to connect to the camera", e);
        }
        	
        super.driverConnect(timestamp);
        //this.addProperty(gainP);
        //this.addProperty(blackP);
        
    }

    @Override
    public void driverDisconnect(Date timestamp) throws INDIException {
    	disconnect();
    	super.driverDisconnect(timestamp);
        
    }
    
    /**
     * A thread to handle the usb events
     * @author farom
     *
     */
	class EventHandlingThread extends Thread {
		/** If thread should abort. */
		private volatile boolean abort;

		/**
		 * Aborts the event handling thread.
		 */
		public void abort() {
			this.abort = true;
		}

		@Override
		public void run() {
			while (!this.abort) {
				int result = LibUsb.handleEventsTimeout(null,100);
				if (result != LibUsb.SUCCESS)
					throw new LibUsbException("Unable to handle events", result);
			}
			LOG.info("Thread aborted");
		}
	}
	
	/**
	 * Connects and initializes the camera
	 * @throws LibUsbException
	 */
	private void connect() throws LibUsbException {
		device = findDevice(VENDOR_ID, PRODUCT_ID);
		if (device == null)
			throw new LibUsbException("Device not found", LibUsb.ERROR_NOT_FOUND);

		LOG.info("Device found");

		handle = new DeviceHandle();
		int result = LibUsb.open(device, handle);
		if (result != LibUsb.SUCCESS)
			throw new LibUsbException("Unable to open USB device", result);

		LOG.info("Device opened");

		result = LibUsb.claimInterface(handle, 0);
		if (result != LibUsb.SUCCESS)
			throw new LibUsbException("Unable to claim interface", result);

		LOG.info("Connection established");

		init();

		connected = true;
	}
	
	/**
	 * Disconnecting the camera
	 */
	private void disconnect(){
		if (connected) {
			int result = LibUsb.releaseInterface(handle, 0);
			if (result != LibUsb.SUCCESS)
				throw new LibUsbException("Unable to release interface", result);
			LibUsb.close(handle);
			System.out.println("Camera disconnected");
			connected = false;
		}
	}
	
	/**
	 * Return the device with the given vendor and product IDs
	 * @param vendorId
	 * @param productId
	 * @return
	 * @throws LibUsbException
	 */
	private Device findDevice(short vendorId, short productId) throws LibUsbException {
		// Read the USB device list
		DeviceList list = new DeviceList();
		int result = LibUsb.getDeviceList(null, list);
		if (result < 0)
			throw new LibUsbException("Unable to get device list", result);

		try {
			// Iterate over all devices and scan for the right one
			for (Device device : list) {
				DeviceDescriptor descriptor = new DeviceDescriptor();
				result = LibUsb.getDeviceDescriptor(device, descriptor);
				if (result != LibUsb.SUCCESS)
					throw new LibUsbException("Unable to read device descriptor", result);
				if (descriptor.idVendor() == vendorId && descriptor.idProduct() == productId)
					return device;
			}
		} finally {
			// Ensure the allocated device list is freed
			LibUsb.freeDeviceList(list, true);
		}

		// Device not found
		return null;
	}
	
	/**
	 * Send initialization commands
	 */
	private void init() {
		// golden pass
		sendAndVerifyControl(0xc0, 17, 8190, 0x0000, new byte[] { 0x00, 0x05, 0x08 });
		sendAndVerifyControl(0xc0, 17, 9216, 0x0000, new byte[] { 0x30, 0x36, 0x08 });
		sendAndVerifyControl(0xc0, 17, 9218, 0x0000, new byte[] { 0x31, 0x31, 0x08 });
		sendAndVerifyControl(0xc0, 17, 9220, 0x0000, new byte[] { 0x30, 0x39, 0x08 });
		sendAndVerifyControl(0xc0, 17, 9222, 0x0000, new byte[] { 0x30, 0x30, 0x08 });
		sendAndVerifyControl(0xc0, 17, 9224, 0x0000, new byte[] { 0x30, 0x36, 0x08 });
		sendAndVerifyControl(0xc0, 17, 9226, 0x0000, new byte[] { 0x32, 0x00, 0x08 });
		sendAndVerifyControl(0x40, 9, 256, 0x000f, new byte[0]);
		try {
			Thread.sleep(100); // 100 milliseconds
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		sendAndVerifyControl(0x40, 9, 512, 0x000f, new byte[0]);
		sendAndVerifyControl(0xc0, 17, 8190, 0x0000, new byte[] { 0x00, 0x05, 0x08 });
		sendAndVerifyControl(0x40, 9, 1024, 0x0001, new byte[0]);
		sendAndVerifyControl(0x40, 3, 8963, 0x0001, new byte[0]);//sendImgCtrl(3, 1);
		try {
			Thread.sleep(100); // 100 milliseconds
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		sendAndVerifyControl(0x40, 3, 13059, 0x0000, new byte[0]);
		sendAndVerifyControl(0x40, 3, 8707, 0x0001, new byte[0]);
		sendAndVerifyControl(0x40, 3, 13571, 0x0000, new byte[0]);
		sendAndVerifyControl(0x40, 3, 50532, 0x067f, new byte[0]);
		sendAndVerifyControl(0x40, 3, 13699, 0x0800, new byte[0]);
		sendAndVerifyControl(0x40, 3, 50654, 0x0ddf, new byte[0]);
		sendAndVerifyControl(0x40, 9, 1024, 0x0000, new byte[0]);
		sendAndVerifyControl(0x40, 9, 1024, 0x0001, new byte[0]);

		sendAndVerifyControl(0x40, 3, 13443, 0x800, new byte[0]);
		sendAndVerifyControl(0x40, 9, 768, 0x0000, new byte[0]);
		sendAndVerifyControl(0x40, 13, 0, 0x0001, new byte[0]);
		sendAndVerifyControl(0x40, 13, 0, 0x0001, new byte[0]);
		sendAndVerifyControl(0x40, 3, 13827, 0x0000, new byte[0]);
		sendAndVerifyControl(0x40, 3, 10035, 0x0301, new byte[0]);

		sendAndVerifyControl(0x40, 3, 53256, 0x00be, new byte[0]);
		sendAndVerifyControl(0x40, 3, 12547, 0x0000, new byte[0]);

		sendAndVerifyControl(0x40, 3, 13443, 0x0800, new byte[0]);
		sendAndVerifyControl(0x40, 9, 768, 0x0000, new byte[0]);
		sendAndVerifyControl(0x40, 13, 0, 0x0001, new byte[0]);
		sendAndVerifyControl(0x40, 13, 0, 0x0001, new byte[0]);
		sendAndVerifyControl(0x40, 5, 15, 0x0003, new byte[0]);

		setGain(750);
		
		LOG.info("Camera initialized");
	}
	
	/**
	 * Send Usb control request and verify the answer, return true if the answer is correct
	 * @param bmRequestType
	 * @param bRequest
	 * @param wIndex
	 * @param wValue
	 * @param trueData
	 * @return
	 */
	private boolean sendAndVerifyControl(int bmRequestType, int bRequest, int wIndex, int wValue, byte[] trueData) {
		byte[] data = new byte[1];
		try {
			data = sendControl((byte) bmRequestType, (byte) bRequest, (short) wIndex, (short) wValue,
					(short) trueData.length);
		} catch (LibUsbException e) {
			LOG.info("Exception raised for the following command:");
			LOG.info("  bmRequestType=" + bmRequestType);
			LOG.info("  bRequest=" + bRequest);
			LOG.info("  wIndex=" + wIndex);
			LOG.info("  wValue=" + wValue);
			LOG.info("  Exception message: " + e.getLocalizedMessage());
			return false;
		}
		if (!Arrays.equals(data, trueData)) {
			LOG.info("Wrong answer to the following command:");
			LOG.info("  bmRequestType=" + bmRequestType);
			LOG.info("  bRequest=" + bRequest);
			LOG.info("  wIndex=" + wIndex);
			LOG.info("  wValue=" + wValue);
			LOG.info("  Received:");
			for (int i = 0; i < data.length; i++) {
				System.out.printf("%02X ", data[i]);
			}
			LOG.info("");
			LOG.info("  Instead of:");
			for (int i = 0; i < trueData.length; i++) {
				System.out.printf("%02X ", trueData[i]);
			}
			LOG.info("");
			return false;
		} else {
			return true;
		}
	}

	/**
	 * Send a control request and return the answer if any
	 * @param bmRequestType
	 * @param bRequest
	 * @param wIndex
	 * @param wValue
	 * @param dataLength
	 * @return
	 * @throws LibUsbException
	 */
	private byte[] sendControl(byte bmRequestType, byte bRequest, short wIndex, short wValue, short dataLength) 
			throws LibUsbException {
		if (bmRequestType >= 0 && dataLength != 0) { // host to device request
														// with undefined data
			LOG.info("host to device request with undefined data: bmRequestType = " + bmRequestType
					+ " and dataLength = " + dataLength);
		}

		ByteBuffer buffer = ByteBuffer.allocateDirect(dataLength);
		int transfered = LibUsb.controlTransfer(handle, bmRequestType, bRequest, wValue, wIndex, buffer, CTRL_TIMEOUT);
		if (transfered != dataLength)
			throw new LibUsbException("Control transfer failed", transfered);

		if (dataLength > 0) {
			byte array[] = new byte[dataLength];
			for (int i = 0; i < dataLength; i++) {
				array[i] = buffer.get(i);
			}
			return array;
		} else {
			return new byte[0];
		}
	}
	
	/**
	 * Change the camera gain
	 * @param gain
	 */
	private void setGain(int gain) throws IllegalArgumentException, LibUsbException{
		if (gain > 1023 || gain < 0) {
			throw new IllegalArgumentException("The gain must be between 0 and 1023. Current value: " + gain);
		}
		sendImgCtrl(IMGCTRL_GAIN, gain);
	}

	/**
	 * Change the camera black level
	 * @param level
	 */
	private void setBlack(int level) throws IllegalArgumentException, LibUsbException{
		if (level > 255 || level < 0) {
			throw new IllegalArgumentException("The black level must be between 0 and 255. Current value: " + level);
		}
		sendImgCtrl(IMGCTRL_BLACK, level);
	}
	
	/**
	 * Send a command to change an image parameter
	 * @param type_int	index of the parameter (See IMGCTRL_XXX constants)
	 * @param value_int	value of the parameter
	 */
	private void sendImgCtrl(int type_int, int value_int) throws LibUsbException{
		short value = (short) value_int;
		short type = (short) type_int;
		
		short wIndex = (short) ((short) ((value ^ (short) 0x33) << 4) | type); // kind of checksum
		
		//System.out.printf("wIndex0 = %04X type = %04X\n", wIndex,type);
		wIndex = (short) (wIndex << 8 | ((wIndex >> 8) & 0xFF));
		//System.out.printf("value = %04X wIndex = %04X\n", value, wIndex);
		sendControl((byte) 0x40, (byte) 0x3, wIndex, value, (short) 0);
	}
	
	/**
	 * Callback for incoming data
	 */
	@Override
	public void processTransfer(Transfer transfer) {

        LOG.info("Bulk transfer: "+transfer.actualLength()+"/"+transfer.length()+" status: " + LibUsb.errorName(transfer.status()));

        if(transfer.actualLength()==IMG_BYTE_SIZE){        

        	transfer.buffer().get(raw, 0, Math.min(transfer.actualLength(),IMG_BYTE_SIZE));
        	LibUsb.freeTransfer(transfer);

	        captureOngoing = false;
        	LOG.info("Image received");
        	
        	
        	INDICCDImage image = INDICCDImage.createImage(SIZE_X, SIZE_Y, BITS_PER_PIXEL_COLOR, ImageType.GRAY_SCALE);

    		primaryCCD.setFrameBuffer(image);
    		
    		PixelIterator it = image.iteratePixel();
    		for(int i = 0; i<SIZE_X*SIZE_Y; i++){
    			int pixelValue = (raw[i*2]&0xFF)*256 + (raw[i*2+1]&0xFF);
    			it.setPixel(pixelValue);
    		}
    		exposureComplete(primaryCCD);
        	
        	    		
        }else if(captureOngoing){
        	requestTransfer(IMG_PACKET_MAXSIZE, IMG_TIMEOUT);
        }
        
        


	}
	
	/**
	 * Request an incomming usb bulk transfer
	 * @param size size of the transfer in byte
	 * @param timeout timeout in ms
	 */
	private void requestTransfer(int size, long timeout){
		ByteBuffer buffer = ByteBuffer.allocateDirect(IMG_PACKET_MAXSIZE);
		Transfer transfer = LibUsb.allocTransfer();
		LibUsb.fillBulkTransfer(transfer, handle, BULK_ENDPOINT, buffer, this, null, timeout);
		transfer.setLength(size);
		int result = LibUsb.submitTransfer(transfer);		
		if (result != LibUsb.SUCCESS) throw new LibUsbException("Unable to submit transfer", result);
	}

	/**
	 * Closing safely the usb related stuff, disconnecting the camera
	 */
	public void close() {
		disconnect();

		thread.abort();
		try {
			thread.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		LibUsb.exit(context);
		System.out.println("LibUsb stopped");
	}

	@Override
	public void finalize() {
		if (!closed) {
			close();
			System.out.println("Call to finalize when not closed");
		}

	}
}
