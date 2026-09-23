package com.nakero.croproute;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.List;

public class RouteSelectorScreen extends Screen {

    private final MinecraftClient client;

    private int selectedIndex = -1;

    public RouteSelectorScreen() {
        super(Text.literal("Rutas guardadas"));
        this.client = MinecraftClient.getInstance();
    }


    @Override
    protected void init() {

        List<RouteData> routes =
                RouteManager.getRoutes();


        int y = 40;


        for (int i = 0; i < routes.size(); i++) {

            final int index = i;

            addDrawableChild(
                    ButtonWidget.builder(
                            Text.literal(
                                    routes.get(i).name
                            ),

                            button -> {

                                selectedIndex = index;

                                RouteManager.setSelectedRoute(
                                        routes.get(index)
                                );

                                CropRouteClient.showActionBar(
                                        "Ruta seleccionada: "
                                                + routes.get(index).name
                                );
                            }

                    ).dimensions(
                            width / 2 - 100,
                            y,
                            200,
                            20

                    ).build()
            );


            y += 25;
        }



        /*
         * Configuración del temporizador
         */

        addDrawableChild(

                ButtonWidget.builder(

                        Text.literal(
                                "Configuración temporizador"
                        ),

                        button -> {

                            if(client != null){

                                client.setScreen(
                                        new TimerConfigScreen()
                                );

                            }

                        }


                ).dimensions(

                        width / 2 - 100,
                        y + 10,
                        200,
                        20

                ).build()

        );


        /*
         * Eliminar ruta
         */

        addDrawableChild(

                ButtonWidget.builder(

                        Text.literal(
                                "Eliminar seleccionada"
                        ),


                        button -> {

                            if(selectedIndex >= 0){

                                RouteManager.deleteRoute(
                                        selectedIndex
                                );

                                if(client != null){

                                    client.setScreen(
                                            new RouteSelectorScreen()
                                    );

                                }

                            }

                        }


                ).dimensions(

                        width / 2 - 100,
                        y + 40,
                        200,
                        20

                ).build()

        );



        /*
         * Cerrar
         */

        addDrawableChild(

                ButtonWidget.builder(

                        Text.literal(
                                "Cerrar"
                        ),

                        button -> close()


                ).dimensions(

                        width / 2 - 100,
                        y + 70,
                        200,
                        20

                ).build()

        );


    }



    @Override
    public void render(
            DrawContext context,
            int mouseX,
            int mouseY,
            float delta
    ){

        renderBackground(
                context
        );


        context.drawCenteredTextWithShadow(

                textRenderer,

                Text.literal(
                        "Rutas guardadas"
                ),

                width / 2,

                15,

                0xFFFFFF
        );


        super.render(
                context,
                mouseX,
                mouseY,
                delta
        );

    }





    /*
     * ======================================================
     * CONFIGURACIÓN DEL TEMPORIZADOR
     * ======================================================
     */

    private class TimerConfigScreen extends Screen {


        private TextFieldWidget hoursField;

        private TextFieldWidget minutesField;



        protected TimerConfigScreen(){

            super(
                    Text.literal(
                            "Temporizador"
                    )
            );

        }



        @Override
        protected void init(){


            hoursField =
                    new TextFieldWidget(

                            textRenderer,

                            width / 2 - 60,

                            70,

                            120,

                            20,

                            Text.literal(
                                    "Horas"
                            )

                    );



            minutesField =
                    new TextFieldWidget(

                            textRenderer,

                            width / 2 - 60,

                            110,

                            120,

                            20,

                            Text.literal(
                                    "Minutos"
                            )

                    );



            hoursField.setText(

                    String.valueOf(
                            RouteManager.getRouteTimerHours()
                    )

            );


            minutesField.setText(

                    String.valueOf(
                            RouteManager.getRouteTimerMinutes()
                    )

            );



            addDrawableChild(
                    hoursField
            );


            addDrawableChild(
                    minutesField
            );





            addDrawableChild(

                    ButtonWidget.builder(

                            Text.literal(
                                    "Guardar"
                            ),


                            button -> {


                                try{


                                    int hours =
                                            Integer.parseInt(
                                                    hoursField.getText()
                                            );


                                    int minutes =
                                            Integer.parseInt(
                                                    minutesField.getText()
                                            );



                                    RouteManager.setRouteTimer(
                                            hours,
                                            minutes
                                    );


                                    CropRouteClient.showActionBar(
                                            "Temporizador guardado"
                                    );



                                    client.setScreen(
                                            new RouteSelectorScreen()
                                    );



                                }catch(Exception e){


                                    CropRouteClient.showActionBar(
                                            "Valores inválidos"
                                    );


                                }



                            }


                    ).dimensions(

                            width / 2 - 60,

                            150,

                            120,

                            20


                    ).build()

            );



            addDrawableChild(

                    ButtonWidget.builder(

                            Text.literal(
                                    "Volver"
                            ),


                            button ->

                                    client.setScreen(
                                            new RouteSelectorScreen()
                                    )


                    ).dimensions(

                            width / 2 - 60,

                            180,

                            120,

                            20


                    ).build()

            );


        }


        @Override
        public void render(
                DrawContext context,
                int mouseX,
                int mouseY,
                float delta
        ){


            renderBackground(
                    context
            );


            context.drawCenteredTextWithShadow(

                    textRenderer,

                    Text.literal(
                            "Tiempo de ruta"
                    ),

                    width / 2,

                    40,

                    0xFFFFFF

            );



            super.render(
                    context,
                    mouseX,
                    mouseY,
                    delta
            );


        }

    }

}
