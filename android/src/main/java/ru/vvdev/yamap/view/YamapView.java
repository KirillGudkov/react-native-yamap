package ru.vvdev.yamap.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.View;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.ReadableType;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.uimanager.events.RCTEventEmitter;
import com.yandex.mapkit.Animation;
import com.yandex.mapkit.RequestPoint;
import com.yandex.mapkit.geometry.BoundingBox;
import com.yandex.mapkit.geometry.Point;
import com.yandex.mapkit.geometry.Polyline;
import com.yandex.mapkit.geometry.SubpolylineHelper;
import com.yandex.mapkit.layers.ObjectEvent;
import com.yandex.mapkit.map.CameraPosition;
import com.yandex.mapkit.map.IconStyle;
import com.yandex.mapkit.map.MapObject;
import com.yandex.mapkit.map.MapObjectCollection;
import com.yandex.mapkit.map.MapObjectTapListener;
import com.yandex.mapkit.map.PlacemarkMapObject;
import com.yandex.mapkit.map.PolygonMapObject;
import com.yandex.mapkit.map.PolylineMapObject;
import com.yandex.mapkit.mapview.MapView;
import com.yandex.mapkit.transport.TransportFactory;
import com.yandex.mapkit.transport.masstransit.MasstransitOptions;
import com.yandex.mapkit.transport.masstransit.MasstransitRouter;
import com.yandex.mapkit.transport.masstransit.PedestrianRouter;
import com.yandex.mapkit.transport.masstransit.Route;
import com.yandex.mapkit.transport.masstransit.RouteStop;
import com.yandex.mapkit.transport.masstransit.Section;
import com.yandex.mapkit.transport.masstransit.SectionMetadata;
import com.yandex.mapkit.transport.masstransit.Session;
import com.yandex.mapkit.transport.masstransit.TimeOptions;
import com.yandex.mapkit.transport.masstransit.Transport;
import com.yandex.mapkit.transport.masstransit.Weight;
import com.yandex.mapkit.user_location.UserLocationLayer;
import com.yandex.mapkit.user_location.UserLocationObjectListener;
import com.yandex.mapkit.user_location.UserLocationView;
import com.yandex.runtime.Error;
import com.yandex.runtime.image.ImageProvider;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import ru.vvdev.yamap.models.PropsStore;
import ru.vvdev.yamap.models.ReactMapObject;
import ru.vvdev.yamap.utils.Callback;
import ru.vvdev.yamap.models.RNMarker;
import ru.vvdev.yamap.utils.ImageLoader;

public class YamapView extends MapView implements Session.RouteListener, MapObjectTapListener, UserLocationObjectListener {

    // default colors for known vehicles
    // "underground" actually get color considering with his own branch"s color
    private final static Map<String, String> DEFAULT_VEHICLE_COLORS = new HashMap<String, String>() {{
        put("bus", "#59ACFF");
        put("railway", "#F8634F");
        put("tramway", "#C86DD7");
        put("suburban", "#3023AE");
        put("underground", "#BDCCDC");
        put("trolleybus", "#55CfDC");
        put("walk", "#333333");
    }};
    private Map<String, String> vehicleColors = DEFAULT_VEHICLE_COLORS;

    private final PropsStore props = new PropsStore();
    private Bitmap userLocationBitmap = null;

    private ArrayList<String> acceptVehicleTypes = new ArrayList<>();
    private ArrayList<RequestPoint> lastKnownRoutePoints = new ArrayList<>();
    private ArrayList<RNMarker> lastKnownMarkers = new ArrayList<>();
    private MasstransitOptions masstransitOptions = new MasstransitOptions(new ArrayList<String>(), acceptVehicleTypes, new TimeOptions());
    private Session walkSession;
    private Session transportSession;
    private ArrayList<PlacemarkMapObject> placemarkObjects = new ArrayList<>();

    WritableArray currentRouteInfo = Arguments.createArray();
    WritableArray routes = Arguments.createArray();

    private MasstransitRouter masstransitRouter = TransportFactory.getInstance().createMasstransitRouter();
    private PedestrianRouter pedestrianRouter = TransportFactory.getInstance().createPedestrianRouter();

    private List<ReactMapObject> childs = new ArrayList();

    // location
    private UserLocationView userLocationView = null;

    public YamapView(Context context) {
        super(context);
        initUserLocationLayer();
    }

    private void initUserLocationLayer() {
        UserLocationLayer userLocationLayer = getMap().getUserLocationLayer();
        userLocationLayer.setObjectListener(this);
        userLocationLayer.setEnabled(true);
        userLocationLayer.setHeadingEnabled(true);
    }

    // ref methods
    public void setCenter(Point location, float zoom) {
        // todo[0]: добавить параметры анимации
        getMap().move(new CameraPosition(location, zoom, 0.0F, 0.0F), new Animation(Animation.Type.SMOOTH, 1.8F), null);
    }

    public void fitAllMarkers() {
        // todo[0]: добавить параметры анимации и дефолтного зума (для одного маркера)
        if (lastKnownMarkers.size() == 0) {
            return;
        }
        if (lastKnownMarkers.size() == 1) {
            Point center = new Point(lastKnownMarkers.get(0).lat, lastKnownMarkers.get(0).lon);
            getMap().move(new CameraPosition(center, 15, 0, 0));
            return;
        }
        double minLon = lastKnownMarkers.get(0).lon;
        double maxLon = lastKnownMarkers.get(0).lon;
        double minLat = lastKnownMarkers.get(0).lat;
        double maxLat = lastKnownMarkers.get(0).lat;
        for (int i = 0; i < lastKnownMarkers.size(); i++) {
            if (lastKnownMarkers.get(i).lon > maxLon) {
                maxLon = lastKnownMarkers.get(i).lon;
            }
            if (lastKnownMarkers.get(i).lon < minLon) {
                minLon = lastKnownMarkers.get(i).lon;
            }
            if (lastKnownMarkers.get(i).lat > maxLat) {
                maxLat = lastKnownMarkers.get(i).lat;
            }
            if (lastKnownMarkers.get(i).lat < minLat) {
                minLat = lastKnownMarkers.get(i).lat;
            }
        }
        Point southWest = new Point(minLat, minLon);
        Point northEast = new Point(maxLat, maxLon);

        BoundingBox boundingBox = new BoundingBox(southWest, northEast);
        CameraPosition cameraPosition = getMap().cameraPosition(boundingBox);
        cameraPosition = new CameraPosition(cameraPosition.getTarget(), cameraPosition.getZoom() - 0.8f, cameraPosition.getAzimuth(), cameraPosition.getTilt());
        getMap().move(cameraPosition, new Animation(Animation.Type.SMOOTH, 0.7f), null);
    }

    // props
    public void setUserLocationIcon(final String iconSource) {
        // todo[0]: можно устанавливать разные иконки на покой и движение. Дополнительно можно устанавливать стиль иконки, например scale
        props.userLocationIcon = iconSource;
        ImageLoader.DownloadImageBitmap(getContext(), iconSource, new Callback<Bitmap>() {
            @Override
            public void invoke(Bitmap bitmap) {
                if (iconSource.equals(props.userLocationIcon)) {
                    userLocationBitmap = bitmap;
                    updateUserLocationIcon();
                }
            }
        });
    }

    public void setMarkers(ArrayList<RNMarker> markers) {
        lastKnownMarkers = markers;
        MapObjectCollection objects = getMap().getMapObjects();
        ArrayList<Boolean> statuses = new ArrayList<>(); // true - уже существовал, false - новый
        try {
            for (int i = 0; i < markers.size(); ++i) {
                statuses.add(i, false);
            }
            for (int i = 0; i < placemarkObjects.size(); ++i) {
                PlacemarkMapObject obj = placemarkObjects.get(i);
                try {
                    JSONObject json = (JSONObject) obj.getUserData();
                    if (json != null) {
                        String id = json.getString("id");
                        boolean removed = true;
                        for (int j = 0; j < markers.size(); ++j) {
                            RNMarker marker = markers.get(j);
                            if (marker.id.equals(id)) {
                                removed = false;
                                statuses.set(j, true);
                                actualizePlacemark(obj, marker);
                                break;
                            }
                        }
                        if (removed) {
                            objects.remove(obj);
                            placemarkObjects.remove(obj);
                            --i;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            for (int j = 0; j < markers.size(); ++j) {
                if (!statuses.get(j)) {
                    RNMarker marker = markers.get(j);
                    PlacemarkMapObject placemark = objects.addPlacemark(new Point(marker.lat, marker.lon));
                    placemarkObjects.add(placemark);
                    try {
                        placemark.setUserData(new JSONObject().put("id", marker.id));
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                    placemark.addTapListener(this);
                    actualizePlacemark(placemark, marker);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setRouteColors(ReadableMap colors) {
        // todo[1]: RERENDER!!!
        // todo[1]: ставить дефолтный если не передан конкретный транспорт. Логично делать в js
        if (colors == null) {
            vehicleColors = DEFAULT_VEHICLE_COLORS;
            return;
        }
        ReadableMapKeySetIterator iterator = colors.keySetIterator();
        while (iterator.hasNextKey()) {
            String key = iterator.nextKey();
            ReadableType type = colors.getType(key);
            if (type != ReadableType.String) {
                throw new IllegalArgumentException("Color prop for \"" + key + "\" should have a String type");
            }
            vehicleColors.put(key, colors.getString(key));
        }
    }

    public void setAcceptVehicleTypes(ArrayList<String> _acceptVehicleTypes) {
        acceptVehicleTypes = _acceptVehicleTypes;
        removeAllSections();
        if (acceptVehicleTypes.isEmpty()) {
            onRoutesFound(Arguments.createArray());
            return;
        }
        if (!lastKnownRoutePoints.isEmpty()) {
            requestRoute(lastKnownRoutePoints);
        }
    }

    public void removeRoute() {
        lastKnownRoutePoints = null;
        removeAllSections();
    }

    public void requestRoute(@Nonnull ArrayList<RequestPoint> points) {
        // todo[1] - все равно не надежно - мог произойти запрос маршрута, затем запрос нового, пока старый еще не найден. Тогда будут найдены и отрисованы оба маршрута
        // todo[2] - нужно делать через ref. Запрос маршрута -> проброс найденых вариантов в js -> запрос из js нарисовать маршруты по id. Удалять аналогично
        lastKnownRoutePoints = points;
        removeAllSections();
        if (acceptVehicleTypes.size() > 0) {
            if (acceptVehicleTypes.contains("walk")) {
                walkSession = pedestrianRouter.requestRoutes(points, new TimeOptions(), this);
                return;
            }
            transportSession = masstransitRouter.requestRoutes(points, masstransitOptions.setAcceptTypes(acceptVehicleTypes), this);
        }
    }

    private void actualizePlacemark(final PlacemarkMapObject placemark, RNMarker marker) {
        placemark.setGeometry(new Point(marker.lat, marker.lon));
        IconStyle style = new IconStyle();
        style.setZIndex((float) marker.zIndex);
        placemark.setIconStyle(style);
        if (!marker.uri.equals("")) {
            ImageLoader.DownloadImageBitmap(getContext(), marker.uri, new Callback<Bitmap>() {
                @Override
                public void invoke(Bitmap bitmap) {
                    placemark.setIcon(ImageProvider.fromBitmap(bitmap));
                }
            });
        }
    }

    @Override
    public void onMasstransitRoutes(@Nonnull List<Route> routes) {
        if (routes.size() > 0) {
            if (acceptVehicleTypes.contains("walk")) {
                processRoute(routes.get(0), 0);
            } else {
                for (int i = 0; i < routes.size(); i++) {
                    processRoute(routes.get(i), i);
                }
            }
            onRoutesFound(this.routes);
            this.routes = Arguments.createArray();
        }
    }

    private void processRoute(Route route, int index) {
        // You need to check the routes and draw this route only if
        // there is at least one transport belonging to the acceptVehicleTypes list
        boolean isRouteBelongToAcceptedVehicleList = false;
        boolean isWalkRoute = true;

        for (Section section : route.getSections()) {
            if (section.getMetadata().getData().getTransports() != null) {
                isWalkRoute = false;
                for (Transport transport : section.getMetadata().getData().getTransports()) {
                    for (String type : transport.getLine().getVehicleTypes()) {
                        if (acceptVehicleTypes.contains(type)) {
                            isRouteBelongToAcceptedVehicleList = true;
                            break;
                        }
                    }
                }
            }
        }

        if (isRouteBelongToAcceptedVehicleList || isWalkRoute) {
            for (Section section : route.getSections()) {
                drawSection(section, SubpolylineHelper.subpolyline(route.getGeometry(),
                        section.getGeometry()), route.getMetadata().getWeight(), index);
            }

            this.routes.pushArray(currentRouteInfo);
            currentRouteInfo = Arguments.createArray();
        }
    }

    @Override
    public void onMasstransitRoutesError(@Nonnull Error error) {
        // todo: implement error handling
        // f.e: emit error event to js
    }

    private void drawSection(final Section section, Polyline geometry, Weight routeWeight, int routeIndex) {
        if (acceptVehicleTypes.isEmpty()) {
            removeAllSections();
            return;
        }

        SectionMetadata.SectionData data = section.getMetadata().getData();
        PolylineMapObject polylineMapObject = getMap().getMapObjects().addCollection().addPolyline(geometry);
        WritableMap routeMetadata = Arguments.createMap();
        WritableMap routeWeightData = Arguments.createMap();
        WritableMap sectionWeightData = Arguments.createMap();
        Map<String, ArrayList<String>> transports = new HashMap<>();
        routeWeightData.putString("time", routeWeight.getTime().getText());
        routeWeightData.putInt("transferCount", routeWeight.getTransfersCount());
        routeWeightData.putDouble("walkingDistance", routeWeight.getWalkingDistance().getValue());
        sectionWeightData.putString("time", section.getMetadata().getWeight().getTime().getText());
        sectionWeightData.putInt("transferCount", section.getMetadata().getWeight().getTransfersCount());
        sectionWeightData.putDouble("walkingDistance", section.getMetadata().getWeight().getWalkingDistance().getValue());
        routeMetadata.putMap("sectionInfo", sectionWeightData);
        routeMetadata.putMap("routeInfo", routeWeightData);
        routeMetadata.putInt("routeIndex", routeIndex);
        final WritableArray stops = new WritableNativeArray();
        for (RouteStop stop : section.getStops()) {
            stops.pushString(stop.getStop().getName());
        }
        routeMetadata.putArray("stops", stops);
        if (data.getTransports() != null) {
            for (Transport transport : data.getTransports()) {
                for (String type : transport.getLine().getVehicleTypes()) {
                    if (type.equals("suburban")) continue;
                    if (transports.get(type) != null) {
                        ArrayList<String> list = transports.get(type);
                        if (list != null) {
                            list.add(transport.getLine().getName());
                            transports.put(type, list);
                        }
                    } else {
                        ArrayList<String> list = new ArrayList<>();
                        list.add(transport.getLine().getName());
                        transports.put(type, list);
                    }
                    routeMetadata.putString("type", type);
                    int color;
                    if (transportHasStyle(transport)) {
                        color = transport.getLine().getStyle().getColor() | 0xFF000000;
                    } else {
                        if (vehicleColors.containsKey(type)) {
                            color = Color.parseColor(vehicleColors.get(type));
                        } else {
                            color = Color.BLACK;
                        }
                    }
                    routeMetadata.putString("sectionColor", formatColor(color));
                    polylineMapObject.setStrokeColor(color);
                }
            }
        } else {
            setDashPolyline(polylineMapObject);
            routeMetadata.putString("sectionColor", formatColor(Color.DKGRAY));
            if (section.getMetadata().getWeight().getWalkingDistance().getValue() == 0) {
                routeMetadata.putString("type", "waiting");
            } else {
                routeMetadata.putString("type", "walk");
            }
        }
        WritableMap wTransports = Arguments.createMap();
        for (Map.Entry<String, ArrayList<String>> entry : transports.entrySet()) {
            wTransports.putArray(entry.getKey(), Arguments.fromList(entry.getValue()));
        }
        routeMetadata.putMap("transports", wTransports);
        currentRouteInfo.pushMap(routeMetadata);
    }

    private void removeAllSections() {
        // todo: удалять только секции
        // todo: вынести clear в отдельный метод, чтобы чистить одновременно
        // todo: не удалять полигоны
        getMap().getMapObjects().clear();
        placemarkObjects.clear();
        setMarkers(lastKnownMarkers);
    }

    public void onRoutesFound(WritableArray routes) {
        WritableMap event = Arguments.createMap();
        event.putArray("routes", routes);
        ReactContext reactContext = (ReactContext) getContext();
        reactContext.getJSModule(RCTEventEmitter.class).receiveEvent(getId(), "routes", event);
    }

    private boolean transportHasStyle(Transport transport) {
        return transport.getLine().getStyle() != null;
    }

    private void setDashPolyline(PolylineMapObject polylineMapObject) {
        polylineMapObject.setDashLength(8f);
        polylineMapObject.setGapLength(11f);
        polylineMapObject.setStrokeColor(Color.parseColor(vehicleColors.get("walk")));
        polylineMapObject.setStrokeWidth(2f);
    }

    private String formatColor(int color) {
        return String.format("#%06X", (0xFFFFFF & color));
    }

    @Override
    public boolean onMapObjectTap(@Nonnull MapObject mapObject, @Nonnull Point point) {
        final Context context = getContext();
        if (context instanceof ReactContext) {
            WritableMap e = Arguments.createMap();
            JSONObject userData = (JSONObject) mapObject.getUserData();
            if (userData != null) {
                try {
                    e.putString("id", userData.get("id").toString());
                } catch (JSONException e1) {
                    e1.printStackTrace();
                }
            }
            ((ReactContext) context).getJSModule(RCTEventEmitter.class).receiveEvent(getId(), "onMarkerPress", e);
        }
        return false;
    }

    // children
    public void addFeature(View child, int index) {
        if (child instanceof YamapPolygon) {
            YamapPolygon polygonChild = (YamapPolygon) child;
            PolygonMapObject obj = getMap().getMapObjects().addPolygon(polygonChild.polygon);
            polygonChild.setMapObject(obj);
            childs.add(polygonChild);
        } else if (child instanceof YamapPolyline) {
            YamapPolyline polylineChild = (YamapPolyline) child;
            PolylineMapObject obj = getMap().getMapObjects().addPolyline(polylineChild.polyline);
            polylineChild.setMapObject(obj);
            childs.add(polylineChild);
        }
    }

    public void removeChild(int index) {
        if (index < childs.size()) {
            ReactMapObject child = childs.remove(index);
            getMap().getMapObjects().remove(child.getMapObject());
        }
    }

    // location listener implementation
    @Override
    public void onObjectAdded(@Nonnull UserLocationView _userLocationView) {
        userLocationView = _userLocationView;
        updateUserLocationIcon();
    }
    @Override
    public void onObjectRemoved(@Nonnull UserLocationView userLocationView) {
    }

    @Override
    public void onObjectUpdated(@Nonnull UserLocationView _userLocationView, @Nonnull ObjectEvent objectEvent) {
        userLocationView = _userLocationView;
        updateUserLocationIcon();
    }

    private void updateUserLocationIcon() {
        if (userLocationView != null && userLocationBitmap != null) {
            PlacemarkMapObject pin = userLocationView.getPin();
            PlacemarkMapObject arrow = userLocationView.getArrow();
            if (userLocationBitmap != null) {
                pin.setIcon(ImageProvider.fromBitmap(userLocationBitmap));
                arrow.setIcon(ImageProvider.fromBitmap(userLocationBitmap));
            }
        }
    }
}